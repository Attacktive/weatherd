# /// script
# requires-python = "==3.13.*"
# dependencies = ["bpy==5.2.2", "numpy==2.5.3", "pillow==12.3.0"]
# ///
"""Generate reproducible first-party broadleaf sprites with independently grown silhouettes."""

import argparse
import math
from dataclasses import dataclass, field
from pathlib import Path
from tempfile import TemporaryDirectory

import bpy
import numpy as np
from mathutils import Vector
from PIL import Image

RENDER_RESOLUTION = 1536
RENDER_SAMPLES = 64
SPRITE_WIDTH = 960
TRANSPARENT_PADDING = 6
SEED = 271

OUTPUT_PATH = Path(__file__).resolve().parents[1] / 'app/src/main/res/drawable-nodpi/tree_broadleaf.png'
COMPANION_OUTPUT_PATH = OUTPUT_PATH.with_name('tree_broadleaf_companion.png')
COMPANION_SEED = 297


def normalize(vec: np.ndarray) -> np.ndarray:
	norm = float(np.linalg.norm(vec))
	if norm < 1e-9:
		return np.array([0.0, 0.0, 1.0], dtype=np.float64)

	return vec / norm


def orthonormal_basis(axis: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
	axis_n = normalize(axis)
	if abs(axis_n[1]) < 0.85:
		ref = np.array([0.0, 1.0, 0.0], dtype=np.float64)
	else:
		ref = np.array([1.0, 0.0, 0.0], dtype=np.float64)

	u_vec = normalize(np.cross(axis_n, ref))
	v_vec = normalize(np.cross(axis_n, u_vec))
	return u_vec, v_vec


@dataclass(frozen=True)
class CanopyLobe:
	"""Ellipsoidal foliage cloud lobe within the tiered broadleaf crown."""

	center: tuple[float, float, float]
	radii: tuple[float, float, float]
	weight: float
	tier: str


@dataclass(frozen=True)
class ChainStyle:
	"""Geometric growth parameters for a subdivided crooked branch chain."""

	crookedness: float = 0.024
	max_seg_len: float = 0.14
	is_trunk: bool = False
	is_deadwood_stub: bool = False
	lobe_tier: str = 'mid'


@dataclass
class SpatialGrid:
	"""Uniform spatial hash grid for 3D neighborhood queries."""

	cell_size: float
	grid: dict[tuple[int, int, int], list[int]] = field(default_factory=dict)

	def _key(self, pt: np.ndarray) -> tuple[int, int, int]:
		return (
			int(math.floor(pt[0] / self.cell_size)),
			int(math.floor(pt[1] / self.cell_size)),
			int(math.floor(pt[2] / self.cell_size)),
		)

	def insert(self, idx: int, pt: np.ndarray) -> None:
		key = self._key(pt)
		self.grid.setdefault(key, []).append(idx)

	def nearby_indices(self, pt: np.ndarray, radius: float) -> list[int]:
		rad_cells = int(math.ceil(radius / self.cell_size))
		kx, ky, kz = self._key(pt)
		offsets = range(-rad_cells, rad_cells + 1)
		cells = [
			(kx + dx, ky + dy, kz + dz)
			for dx in offsets
			for dy in offsets
			for dz in offsets
		]

		result: list[int] = []
		for cell in cells:
			result.extend(self.grid.get(cell, ()))

		return result


@dataclass
class TreeNode:
	"""Graph node representing a point along the trunk, scaffold bough, or twig."""

	idx: int
	pos: np.ndarray
	parent_idx: int | None = None
	is_trunk: bool = False
	is_deadwood_stub: bool = False
	lobe_tier: str = 'mid'
	children: list[int] = field(default_factory=list)
	radius: float = 0.004
	dist_to_tip: float = 0.0


@dataclass(frozen=True)
class RingFrame:
	"""Cross-section ring discretization for tubular branch mesh construction."""

	k_rings: int
	angles: np.ndarray
	cos_a: np.ndarray
	sin_a: np.ndarray

	@classmethod
	def create(cls, k_rings: int) -> 'RingFrame':
		angles = np.linspace(0.0, 2.0 * np.pi, k_rings, endpoint=False)
		return cls(
			k_rings=k_rings,
			angles=angles,
			cos_a=np.cos(angles),
			sin_a=np.sin(angles),
		)


def define_canopy_lobes() -> list[CanopyLobe]:
	return [
		CanopyLobe((-0.48, 0.04, 4.22), (1.05, 0.96, 0.52), 1.35, 'dome'),
		CanopyLobe((0.34, 0.06, 4.14), (1.02, 0.94, 0.48), 1.25, 'dome'),
		CanopyLobe((-1.42, -0.04, 3.96), (0.94, 0.86, 0.48), 1.22, 'dome'),
		CanopyLobe((1.28, -0.04, 3.88), (0.92, 0.84, 0.46), 1.15, 'dome'),
		CanopyLobe((-0.18, -0.48, 3.98), (0.94, 0.82, 0.44), 0.95, 'dome'),
		CanopyLobe((-0.22, 0.50, 4.00), (0.94, 0.82, 0.44), 0.95, 'dome'),
		CanopyLobe((-0.92, 0.06, 3.44), (1.04, 0.90, 0.38), 1.12, 'mid'),
		CanopyLobe((0.02, -0.04, 3.40), (1.08, 0.92, 0.38), 1.15, 'mid'),
		CanopyLobe((0.92, 0.06, 3.38), (1.04, 0.88, 0.38), 1.12, 'mid'),
		CanopyLobe((-1.48, -0.44, 3.26), (0.80, 0.70, 0.34), 0.80, 'mid'),
		CanopyLobe((-1.52, 0.46, 3.28), (0.80, 0.70, 0.34), 0.80, 'mid'),
		CanopyLobe((1.48, -0.44, 3.18), (0.80, 0.70, 0.34), 0.80, 'mid'),
		CanopyLobe((1.52, 0.46, 3.22), (0.80, 0.70, 0.34), 0.80, 'mid'),
		CanopyLobe((-2.42, 0.02, 3.20), (0.84, 0.74, 0.36), 1.04, 'mid'),
		CanopyLobe((-1.96, -0.04, 3.36), (0.76, 0.68, 0.34), 0.86, 'mid'),
		CanopyLobe((-2.58, 0.04, 2.84), (0.64, 0.56, 0.26), 0.72, 'mid'),
		CanopyLobe((2.38, 0.04, 3.08), (0.86, 0.76, 0.36), 1.06, 'mid'),
		CanopyLobe((1.92, -0.04, 3.28), (0.78, 0.68, 0.34), 0.88, 'mid'),
		CanopyLobe((2.64, -0.02, 2.74), (0.68, 0.58, 0.28), 0.76, 'mid'),
		CanopyLobe((-0.44, -0.12, 2.86), (0.68, 0.58, 0.25), 0.68, 'lower_pad'),
		CanopyLobe((0.48, 0.12, 2.84), (0.68, 0.58, 0.25), 0.68, 'lower_pad'),
		CanopyLobe((-0.26, 0.06, 2.60), (0.58, 0.50, 0.22), 0.56, 'lower_pad'),
		CanopyLobe((0.32, -0.06, 2.58), (0.58, 0.50, 0.22), 0.56, 'lower_pad'),
		CanopyLobe((-1.22, 0.10, 2.76), (0.68, 0.58, 0.24), 0.66, 'lower_pad'),
		CanopyLobe((1.24, -0.10, 2.74), (0.68, 0.58, 0.24), 0.66, 'lower_pad'),
		CanopyLobe((-0.86, -0.08, 2.40), (0.46, 0.40, 0.16), 0.40, 'skirt_tip'),
		CanopyLobe((0.90, 0.08, 2.38), (0.46, 0.40, 0.16), 0.40, 'skirt_tip'),
		CanopyLobe((-1.62, 0.12, 2.42), (0.52, 0.46, 0.18), 0.48, 'skirt_tip'),
		CanopyLobe((1.68, -0.12, 2.38), (0.54, 0.46, 0.18), 0.48, 'skirt_tip'),
		CanopyLobe((-3.06, 0.02, 2.56), (0.60, 0.52, 0.21), 0.72, 'skirt_tip'),
		CanopyLobe((-3.40, -0.05, 2.48), (0.36, 0.32, 0.15), 0.40, 'skirt_tip'),
		CanopyLobe((-2.24, -0.16, 2.46), (0.54, 0.48, 0.18), 0.50, 'skirt_tip'),
		CanopyLobe((3.08, 0.04, 2.44), (0.66, 0.56, 0.21), 0.78, 'skirt_tip'),
		CanopyLobe((3.48, -0.04, 2.32), (0.40, 0.36, 0.15), 0.46, 'skirt_tip'),
		CanopyLobe((2.32, 0.16, 2.40), (0.58, 0.50, 0.19), 0.54, 'skirt_tip'),
	]


def define_companion_canopy_lobes() -> list[CanopyLobe]:
	"""A narrower, uneven crown with a high left leader and a low right shoulder."""
	return [
		CanopyLobe((-0.38, 0.04, 4.22), (0.68, 0.66, 0.52), 1.25, 'dome'),
		CanopyLobe((0.30, -0.08, 3.96), (0.74, 0.68, 0.48), 1.15, 'dome'),
		CanopyLobe((-0.88, 0.02, 3.70), (0.66, 0.64, 0.42), 1.05, 'mid'),
		CanopyLobe((0.86, 0.06, 3.40), (0.72, 0.64, 0.46), 1.10, 'mid'),
		CanopyLobe((-0.22, -0.42, 3.42), (0.80, 0.62, 0.44), 1.10, 'mid'),
		CanopyLobe((0.14, 0.42, 3.50), (0.70, 0.64, 0.42), 1.00, 'mid'),
		CanopyLobe((-1.12, 0.04, 2.98), (0.58, 0.54, 0.36), 0.90, 'mid'),
		CanopyLobe((0.82, -0.16, 2.74), (0.72, 0.58, 0.34), 0.95, 'lower_pad'),
		CanopyLobe((-0.26, 0.10, 2.82), (0.74, 0.62, 0.32), 1.00, 'lower_pad'),
		CanopyLobe((-1.26, -0.06, 2.52), (0.44, 0.42, 0.24), 0.60, 'skirt_tip'),
		CanopyLobe((1.22, 0.04, 2.34), (0.48, 0.44, 0.24), 0.65, 'skirt_tip'),
	]


def sample_canopy_attractors(
	rng: np.random.Generator,
	lobes: list[CanopyLobe],
) -> tuple[np.ndarray, list[str]]:
	total_attractors = 6200
	weights = np.array([lobe.weight for lobe in lobes], dtype=np.float64)
	probs = weights / np.sum(weights)

	points: list[np.ndarray] = []
	tiers: list[str] = []

	while len(points) < total_attractors:
		lobe_idx = int(rng.choice(len(lobes), p=probs))
		lobe = lobes[lobe_idx]
		cx, cy, cz = lobe.center
		rx, ry, rz = lobe.radii

		u_dir = normalize(rng.normal(size=3))
		r_mag = float(rng.uniform(0.12, 1.0) ** (1.0 / 3.0))
		local = u_dir * r_mag

		puff = 1.0 + 0.11 * math.sin(local[0] * 3.8 + cx) * math.cos(local[1] * 3.8 + cy)
		pt = np.array(
			[
				cx + local[0] * rx * puff,
				cy + local[1] * ry * puff,
				cz + local[2] * rz * puff,
			],
			dtype=np.float64,
		)

		points.append(pt)
		tiers.append(lobe.tier)

	return np.array(points, dtype=np.float64), tiers


def append_polychain(
	nodes: list[TreeNode],
	parent_idx: int,
	waypoints: list[tuple[float, float, float]],
	rng: np.random.Generator,
	style: ChainStyle,
) -> list[int]:
	created: list[int] = []
	cur_parent = parent_idx
	prev_pos = nodes[cur_parent].pos.copy()

	for wp in waypoints:
		target = np.array(wp, dtype=np.float64)
		seg_vec = target - prev_pos
		seg_len = float(np.linalg.norm(seg_vec))
		steps = max(1, int(math.ceil(seg_len / style.max_seg_len)))
		u_vec, v_vec = orthonormal_basis(seg_vec)

		for s in range(1, steps + 1):
			t = s / steps
			interp = prev_pos + seg_vec * t
			if s < steps and style.crookedness > 0.0:
				envelope = math.sin(math.pi * t)
				lat_a = float(rng.normal(0.0, style.crookedness)) * envelope
				lat_b = float(rng.normal(0.0, style.crookedness * 0.7)) * envelope
				interp = interp + u_vec * lat_a + v_vec * lat_b

			idx = len(nodes)
			node = TreeNode(
				idx=idx,
				pos=interp.astype(np.float64),
				parent_idx=cur_parent,
				is_trunk=style.is_trunk,
				is_deadwood_stub=style.is_deadwood_stub,
				lobe_tier=style.lobe_tier,
			)

			nodes.append(node)
			nodes[cur_parent].children.append(idx)
			created.append(idx)
			cur_parent = idx

		prev_pos = nodes[cur_parent].pos.copy()

	return created


def primary_scaffold_specs(
	fork_left_split: int,
	fork_center_base: int,
	fork_right_split: int,
) -> list[tuple[int, list[tuple[float, float, float]], float, str, list[tuple[float, list[tuple[float, float, float]], str]]]]:
	return [
		(
			fork_left_split,
			[
				(-1.18, -0.02, 2.26),
				(-1.68, -0.06, 2.22),
				(-2.22, 0.02, 2.34),
				(-2.78, -0.04, 2.46),
				(-3.28, -0.02, 2.44),
			],
			0.026,
			'skirt_tip',
			[
				(0.22, [(-1.38, 0.14, 2.52), (-1.82, 0.18, 2.86), (-2.32, 0.06, 3.14), (-2.68, -0.04, 3.18)], 'mid'),
				(0.55, [(-2.12, -0.12, 2.52), (-2.58, -0.16, 2.74), (-2.96, -0.08, 2.76)], 'mid'),
				(0.38, [(-1.56, 0.10, 2.14), (-1.98, 0.14, 2.08), (-2.40, 0.08, 2.18), (-2.76, 0.04, 2.28)], 'skirt_tip'),
			],
		),
		(
			fork_left_split,
			[
				(-0.96, 0.06, 2.48),
				(-1.22, -0.02, 2.94),
				(-1.44, 0.04, 3.44),
				(-1.48, -0.04, 3.88),
				(-1.24, 0.02, 4.20),
			],
			0.025,
			'dome',
			[
				(0.24, [(-0.68, -0.10, 2.78), (-0.64, -0.04, 3.34), (-0.54, 0.04, 3.90), (-0.42, 0.02, 4.36)], 'dome'),
				(0.46, [(-1.56, -0.18, 3.12), (-1.92, -0.14, 3.52)], 'mid'),
				(0.66, [(-1.68, 0.16, 3.56), (-1.98, 0.10, 3.80)], 'mid'),
			],
		),
		(
			fork_center_base,
			[
				(0.08, 0.04, 2.04),
				(-0.06, 0.08, 2.42),
				(-0.22, 0.04, 2.86),
				(-0.26, -0.04, 3.42),
				(-0.16, 0.02, 4.18),
			],
			0.025,
			'dome',
			[
				(0.32, [(0.26, -0.10, 2.68), (0.44, -0.06, 3.22), (0.42, 0.04, 3.78), (0.32, 0.04, 4.16)], 'dome'),
				(0.26, [(-0.36, -0.14, 2.48), (-0.72, -0.18, 2.66)], 'lower_pad'),
				(0.48, [(0.24, 0.16, 2.74), (0.62, 0.20, 2.88)], 'lower_pad'),
			],
		),
		(
			fork_right_split,
			[
				(0.92, 0.02, 2.46),
				(1.20, 0.06, 2.90),
				(1.42, 0.08, 3.36),
				(1.46, -0.02, 3.74),
				(1.24, 0.04, 3.98),
			],
			0.025,
			'dome',
			[
				(0.26, [(0.68, 0.14, 2.74), (0.82, 0.10, 3.26), (0.94, 0.02, 3.78)], 'dome'),
				(0.54, [(1.62, -0.18, 3.24), (1.98, -0.12, 3.48)], 'mid'),
			],
		),
		(
			fork_right_split,
			[
				(1.16, 0.04, 2.24),
				(1.70, 0.06, 2.18),
				(2.26, -0.04, 2.26),
				(2.86, 0.02, 2.34),
				(3.42, -0.04, 2.26),
			],
			0.026,
			'skirt_tip',
			[
				(0.22, [(1.36, 0.12, 2.50), (1.78, 0.16, 2.82), (2.24, 0.06, 3.04), (2.62, -0.02, 3.08)], 'mid'),
				(0.56, [(2.18, 0.14, 2.46), (2.68, 0.16, 2.68), (3.08, 0.08, 2.66)], 'mid'),
				(0.38, [(1.52, -0.10, 2.08), (1.96, -0.14, 2.02), (2.42, -0.08, 2.10), (2.82, -0.04, 2.16)], 'skirt_tip'),
			],
		),
		(
			fork_left_split,
			[
				(-0.92, -0.32, 2.34),
				(-1.14, -0.58, 2.74),
				(-1.32, -0.54, 3.22),
			],
			0.024,
			'mid',
			[
				(0.48, [(-0.78, -0.68, 2.86), (-0.48, -0.58, 3.34)], 'mid'),
			],
		),
		(
			fork_right_split,
			[
				(0.88, 0.32, 2.32),
				(1.12, 0.58, 2.72),
				(1.30, 0.54, 3.20),
			],
			0.024,
			'mid',
			[
				(0.48, [(0.74, 0.68, 2.84), (0.44, 0.58, 3.32)], 'mid'),
			],
		),
	]


def attach_secondary_branches(
	nodes: list[TreeNode],
	chain: list[int],
	crook: float,
	sub_specs: list[tuple[float, list[tuple[float, float, float]], str]],
	rng: np.random.Generator,
) -> None:
	for frac, sub_wps, sub_tier in sub_specs:
		attach_idx = chain[min(len(chain) - 1, max(0, int(round(frac * (len(chain) - 1)))))]
		sub_style = ChainStyle(crookedness=crook * 0.9, max_seg_len=0.13, lobe_tier=sub_tier)
		sub_chain = append_polychain(nodes, attach_idx, sub_wps, rng, sub_style)

		if len(sub_chain) >= 3:
			tert_attach = sub_chain[len(sub_chain) // 2]
			base_pos = nodes[tert_attach].pos
			end_pos = nodes[sub_chain[-1]].pos
			dir_v = normalize(end_pos - base_pos)
			side = normalize(np.array([-dir_v[1], dir_v[0], 0.16], dtype=np.float64))
			tert_end = base_pos + dir_v * 0.40 + side * float(rng.choice([-0.32, 0.32]))
			tert_style = ChainStyle(crookedness=0.020, max_seg_len=0.12, lobe_tier=sub_tier)
			append_polychain(nodes, tert_attach, [tuple(tert_end)], rng, tert_style)


def build_deliquescent_scaffold(
	nodes: list[TreeNode],
	rng: np.random.Generator,
) -> list[int]:
	root_node = TreeNode(
		idx=0,
		pos=np.array([-0.18, 0.00, 0.00], dtype=np.float64),
		parent_idx=None,
		is_trunk=True,
	)

	nodes.append(root_node)

	trunk_waypoints = [
		(-0.09, -0.01, 0.24),
		(0.07, -0.02, 0.50),
		(0.22, -0.03, 0.74),
		(0.26, -0.02, 0.90),
		(0.14, 0.01, 1.10),
		(-0.03, 0.02, 1.30),
		(-0.13, 0.02, 1.44),
		(-0.08, 0.01, 1.58),
	]

	trunk_style = ChainStyle(crookedness=0.007, max_seg_len=0.13, is_trunk=True)
	trunk_chain = append_polychain(nodes, 0, trunk_waypoints, rng, trunk_style)
	trunk_indices = [0] + trunk_chain

	spur_anchor = min(trunk_indices, key=lambda idx: abs(nodes[idx].pos[2] - 1.42))
	spur_style = ChainStyle(crookedness=0.008, max_seg_len=0.08, is_deadwood_stub=True)
	append_polychain(
		nodes,
		spur_anchor,
		[(-0.24, -0.02, 1.46), (-0.35, -0.03, 1.52), (-0.42, -0.01, 1.50)],
		rng,
		spur_style,
	)

	trunk_top = trunk_indices[-1]
	primary_bough_style = ChainStyle(crookedness=0.014, max_seg_len=0.12, lobe_tier='mid')
	left_primary = append_polychain(
		nodes,
		trunk_top,
		[(-0.24, 0.02, 1.72), (-0.48, 0.04, 1.90), (-0.74, 0.05, 2.08)],
		rng,
		primary_bough_style,
	)

	right_primary = append_polychain(
		nodes,
		trunk_top,
		[(0.14, -0.01, 1.70), (0.40, -0.04, 1.88), (0.68, -0.05, 2.06)],
		rng,
		primary_bough_style,
	)

	specs = primary_scaffold_specs(left_primary[-1], right_primary[0], right_primary[-1])
	for anchor_idx, waypoints, crook, tier, sub_specs in specs:
		p_style = ChainStyle(crookedness=crook, max_seg_len=0.14, lobe_tier=tier)
		chain = append_polychain(nodes, anchor_idx, waypoints, rng, p_style)
		attach_secondary_branches(nodes, chain, crook, sub_specs, rng)

	return trunk_indices


def build_companion_scaffold(nodes: list[TreeNode], rng: np.random.Generator) -> list[int]:
	"""Grow a separate leaning trunk and staggered boughs, not a transformed hero mesh."""
	nodes.append(TreeNode(idx=0, pos=np.array([0.12, 0.0, 0.0]), is_trunk=True))
	trunk_waypoints = [
		(0.04, 0.00, 0.30),
		(-0.14, -0.03, 0.62),
		(-0.10, -0.04, 0.96),
		(0.10, 0.02, 1.28),
		(0.26, 0.04, 1.62),
		(0.18, 0.02, 1.96),
		(-0.04, 0.00, 2.30),
		(-0.18, 0.02, 2.68),
		(-0.12, 0.04, 3.12),
		(-0.34, 0.02, 3.64),
		(-0.38, 0.00, 4.14),
	]
	trunk_style = ChainStyle(crookedness=0.010, max_seg_len=0.13, is_trunk=True)
	trunk_indices = [0] + append_polychain(nodes, 0, trunk_waypoints, rng, trunk_style)
	boughs = [
		(1.62, [(-0.30, 0.06, 1.92), (-0.80, 0.02, 2.22), (-1.28, -0.02, 2.48)], 'skirt_tip'),
		(1.96, [(0.56, -0.04, 2.08), (1.02, -0.02, 2.22), (1.36, 0.02, 2.36)], 'skirt_tip'),
		(2.30, [(-0.62, -0.10, 2.58), (-1.06, -0.06, 2.90), (-1.22, 0.04, 3.16)], 'mid'),
		(2.30, [(0.38, 0.10, 2.56), (0.82, 0.14, 2.96), (0.92, 0.06, 3.40)], 'mid'),
		(2.68, [(-0.62, 0.04, 3.00), (-0.90, 0.06, 3.44), (-0.82, 0.00, 3.82)], 'dome'),
		(3.12, [(0.24, -0.08, 3.40), (0.50, -0.04, 3.80), (0.42, 0.02, 4.08)], 'dome'),
		(2.68, [(-0.12, -0.40, 2.98), (0.12, -0.52, 3.36), (0.18, -0.46, 3.62)], 'mid'),
		(3.12, [(-0.32, 0.38, 3.36), (-0.48, 0.44, 3.74), (-0.56, 0.34, 4.06)], 'dome'),
	]

	for height, waypoints, tier in boughs:
		anchor = min(trunk_indices, key=lambda idx: abs(nodes[idx].pos[2] - height))
		style = ChainStyle(crookedness=0.022, max_seg_len=0.12, lobe_tier=tier)
		append_polychain(nodes, anchor, waypoints, rng, style)

	return trunk_indices


def closest_grid_node(
	nodes: list[TreeNode],
	attr: np.ndarray,
	nearby: list[int],
) -> tuple[int | None, float]:
	best_node_idx: int | None = None
	min_dist = 1e9
	for n_idx in nearby:
		dist = float(np.linalg.norm(attr - nodes[n_idx].pos))
		if dist < min_dist:
			min_dist = dist
			best_node_idx = n_idx

	return best_node_idx, min_dist


def build_colonization_grid(nodes: list[TreeNode], d_influence: float) -> SpatialGrid:
	grid = SpatialGrid(cell_size=d_influence)
	for node in nodes:
		if not (node.is_trunk or node.is_deadwood_stub):
			grid.insert(node.idx, node.pos)

	return grid


def record_attractor_hit(
	nodes: list[TreeNode],
	a_idx: int,
	attr: np.ndarray,
	tier: str,
	state: tuple[SpatialGrid, dict[int, list[np.ndarray]], dict[int, list[str]], set[int]],
) -> None:
	grid, node_dirs, node_votes, kill_set = state
	nearby = grid.nearby_indices(attr, grid.cell_size)
	best_idx, min_dist = closest_grid_node(nodes, attr, nearby)
	if best_idx is None:
		return

	if min_dist < 0.16:
		kill_set.add(a_idx)
	elif min_dist <= grid.cell_size:
		node_dirs.setdefault(best_idx, []).append(normalize(attr - nodes[best_idx].pos))
		node_votes.setdefault(best_idx, []).append(tier)


def collect_attractor_influences(
	nodes: list[TreeNode],
	active_attractors: np.ndarray,
	active_tiers: list[str],
	d_influence: float,
) -> tuple[dict[int, list[np.ndarray]], dict[int, list[str]], set[int]]:
	grid = build_colonization_grid(nodes, d_influence)
	node_dirs: dict[int, list[np.ndarray]] = {}
	node_votes: dict[int, list[str]] = {}
	kill_set: set[int] = set()
	state = (grid, node_dirs, node_votes, kill_set)

	for a_idx, attr in enumerate(active_attractors):
		record_attractor_hit(nodes, a_idx, attr, active_tiers[a_idx], state)

	return node_dirs, node_votes, kill_set


def compute_colonization_dir(
	parent_node: TreeNode,
	dirs: list[np.ndarray],
	rng: np.random.Generator,
) -> np.ndarray:
	avg_dir = normalize(np.mean(dirs, axis=0))
	radial_xy = np.array([parent_node.pos[0], parent_node.pos[1], 0.0], dtype=np.float64)
	if np.linalg.norm(radial_xy) > 1e-4:
		radial_out = normalize(radial_xy)
	else:
		radial_out = np.array([1.0, 0.0, 0.0], dtype=np.float64)

	combined = 0.72 * avg_dir + 0.28 * radial_out
	combined[2] = min(0.50, float(combined[2]))

	u_vec, v_vec = orthonormal_basis(combined)
	kink = u_vec * float(rng.normal(0.0, 0.15)) + v_vec * float(rng.normal(0.0, 0.11))
	return normalize(combined + kink)


def can_grow_in_dir(nodes: list[TreeNode], parent_node: TreeNode, grow_dir: np.ndarray) -> bool:
	if parent_node.parent_idx is None:
		return True

	parent_dir = normalize(parent_node.pos - nodes[parent_node.parent_idx].pos)
	return float(np.dot(grow_dir, parent_dir)) >= -0.15


def select_dominant_tier(votes: list[str], fallback: str) -> str:
	if not votes:
		return fallback

	return max(sorted(set(votes)), key=votes.count)


def step_colonization_nodes(
	nodes: list[TreeNode],
	node_dirs: dict[int, list[np.ndarray]],
	node_votes: dict[int, list[str]],
	rng: np.random.Generator,
	step_length: float,
) -> int:
	new_nodes_count = 0
	for n_idx, dirs in node_dirs.items():
		parent_node = nodes[n_idx]
		if len(parent_node.children) >= 3:
			continue

		grow_dir = compute_colonization_dir(parent_node, dirs, rng)
		if not can_grow_in_dir(nodes, parent_node, grow_dir):
			continue

		dominant_tier = select_dominant_tier(node_votes[n_idx], parent_node.lobe_tier)
		new_idx = len(nodes)
		new_node = TreeNode(
			idx=new_idx,
			pos=(parent_node.pos + grow_dir * step_length).astype(np.float64),
			parent_idx=n_idx,
			lobe_tier=dominant_tier,
		)

		nodes.append(new_node)
		parent_node.children.append(new_idx)
		new_nodes_count += 1

	return new_nodes_count


def prune_killed_attractors(
	active_attractors: np.ndarray,
	active_tiers: list[str],
	kill_set: set[int],
) -> tuple[np.ndarray, list[str]]:
	if not kill_set:
		return active_attractors, active_tiers

	keep_mask = [i not in kill_set for i in range(len(active_attractors))]
	kept_tiers = [tier for i, tier in enumerate(active_tiers) if keep_mask[i]]
	return active_attractors[keep_mask], kept_tiers


def grow_space_colonization(
	nodes: list[TreeNode],
	attractors: np.ndarray,
	attractor_tiers: list[str],
	rng: np.random.Generator,
) -> None:
	active_attractors = np.copy(attractors)
	active_tiers = list(attractor_tiers)

	for _ in range(32):
		if len(active_attractors) == 0:
			break

		node_dirs, node_votes, kill_set = collect_attractor_influences(
			nodes,
			active_attractors,
			active_tiers,
			0.86,
		)

		active_attractors, active_tiers = prune_killed_attractors(active_attractors, active_tiers, kill_set)
		if not node_dirs or step_colonization_nodes(nodes, node_dirs, node_votes, rng, 0.105) == 0:
			break


def traversal_order(nodes: list[TreeNode]) -> list[int]:
	order: list[int] = []
	stack = [0]
	while stack:
		curr = stack.pop()
		order.append(curr)
		stack.extend(nodes[curr].children)

	return order


def tip_or_step_radius(node: TreeNode, tip_radius: float) -> tuple[float, float]:
	if node.is_deadwood_stub:
		return 0.010, 0.0085

	return tip_radius, 0.0005


def max_child_distance(node: TreeNode, nodes: list[TreeNode]) -> float:
	best = 0.0
	for ch in node.children:
		dist = nodes[ch].dist_to_tip + float(np.linalg.norm(nodes[ch].pos - node.pos))
		if dist > best:
			best = dist

	return best


def update_single_node_murray(
	node: TreeNode,
	nodes: list[TreeNode],
	alpha: float,
	tip_radius: float,
) -> None:
	leaf_r, step_add = tip_or_step_radius(node, tip_radius)
	if not node.children:
		node.radius = leaf_r
		node.dist_to_tip = 0.0
		return

	child_radii = [nodes[ch].radius for ch in node.children]
	calc_radius = sum(r**alpha for r in child_radii) ** (1.0 / alpha)
	node.radius = max(calc_radius, max(child_radii) + step_add)
	node.dist_to_tip = max_child_distance(node, nodes)


def apply_trunk_profile(nodes: list[TreeNode], trunk_indices: list[int]) -> None:
	n_trunk = len(trunk_indices)
	for i, t_idx in enumerate(trunk_indices):
		frac = i / max(1, n_trunk - 1)
		root_flare = 0.052 * ((1.0 - min(1.0, frac / 0.32)) ** 2.2)
		nodes[t_idx].radius = 0.168 * (1.0 - 0.226 * frac) + root_flare


def calculate_radii_murray(
	nodes: list[TreeNode],
	trunk_indices: list[int],
) -> None:
	tip_radius = 0.0036
	for idx in reversed(traversal_order(nodes)):
		update_single_node_murray(nodes[idx], nodes, 2.25, tip_radius)

	raw_fork_radius = nodes[trunk_indices[-1]].radius
	branch_scale = 0.130 / max(raw_fork_radius, 0.04)
	trunk_set = set(trunk_indices)

	for node in nodes:
		if not (node.idx in trunk_set or node.is_deadwood_stub):
			weight = min(1.0, max(0.0, (node.radius - tip_radius) / max(0.001, raw_fork_radius - tip_radius)))
			eff_scale = 1.0 + (branch_scale - 1.0) * (weight ** 0.22)
			upper_taper = 1.0 - 0.38 * min(1.0, max(0.0, (node.pos[2] - 2.75) / 1.30))
			node.radius = max(tip_radius, node.radius * eff_scale * upper_taper)

	apply_trunk_profile(nodes, trunk_indices)


def node_point_4d(node: TreeNode, radius_override: float | None = None) -> list[float]:
	if radius_override is None:
		rad = node.radius
	else:
		rad = radius_override

	return [
		round(float(node.pos[0]), 4),
		round(float(node.pos[1]), 4),
		round(float(node.pos[2]), 4),
		round(float(rad), 4),
	]


def advance_branch_chain(
	nodes: list[TreeNode],
	curr: int,
	branch_id: int,
	stack: list[tuple[int, int | None, list[list[float]]]],
) -> int | None:
	node = nodes[curr]
	if not node.children:
		return None

	if len(node.children) == 1:
		return node.children[0]

	ordered = sorted(
		node.children,
		key=lambda c: (nodes[c].is_trunk, nodes[c].radius, nodes[c].dist_to_tip),
		reverse=True,
	)

	for sc in reversed(ordered[1:]):
		fork_r = min(node.radius * 0.88, nodes[sc].radius * 1.05)
		stack.append((sc, branch_id, [node_point_4d(node, fork_r)]))

	return ordered[0]


def decompose_into_branches(nodes: list[TreeNode]) -> list[dict]:
	branches: list[dict] = []
	stack: list[tuple[int, int | None, list[list[float]]]] = [(0, None, [])]

	while stack:
		start_idx, parent_branch_id, prefix = stack.pop()
		branch_id = len(branches)
		pts: list[list[float]] = list(prefix)
		curr: int | None = start_idx

		while curr is not None:
			pts.append(node_point_4d(nodes[curr]))
			curr = advance_branch_chain(nodes, curr, branch_id, stack)

		branches.append({
			'id': branch_id,
			'parent': parent_branch_id,
			'points': pts,
		})

	return branches


def leaf_count_for_node(
	node: TreeNode,
	is_terminal: bool,
	rng: np.random.Generator,
) -> int:
	bounds_map = {
		('skirt_tip', True): (38, 52),
		('skirt_tip', False): (16, 25),
		('lower_pad', True): (38, 50),
		('lower_pad', False): (18, 26),
		('mid', True): (60, 80),
		('mid', False): (30, 44),
		('dome', True): (60, 80),
		('dome', False): (30, 44),
	}

	low, high = bounds_map[(node.lobe_tier, is_terminal)]
	return int(rng.integers(low, high))


def is_in_subcanopy_pocket(leaf_center: np.ndarray) -> bool:
	in_z = 2.38 < leaf_center[2] < 2.74
	in_xy = abs(leaf_center[0]) < 1.95 and abs(leaf_center[1]) < 0.45
	if not (in_z and in_xy):
		return False

	pocket_val = math.sin(leaf_center[0] * 3.7 + leaf_center[1] * 2.5 + 0.4) * math.cos(leaf_center[2] * 12.0)
	return pocket_val > 0.72


def classify_leaf_tone(leaf_center: np.ndarray, rng: np.random.Generator) -> int:
	rel_h = (leaf_center[2] - 2.25) / 2.40
	front_exposure = max(-0.4, min(0.5, -leaf_center[1] * 0.45))
	sun_dot = (-0.36 * leaf_center[0] - 0.32 * leaf_center[1] + 0.88 * (leaf_center[2] - 2.85)) / 3.2
	score = 0.44 * rel_h + 0.34 * sun_dot + 0.22 * front_exposure + float(rng.uniform(-0.12, 0.14))
	thresholds = (0.18, 0.36, 0.55, 0.74)
	return sum(1 for thresh in thresholds if score >= thresh)


def tuft_sampling_bounds(is_terminal: bool) -> tuple[float, float, float, float]:
	if is_terminal:
		return (0.14, 1.0, 0.02, 0.195)

	return (0.06, 0.98, 0.02, 0.150)


def sample_single_leaf(
	parent_pos: np.ndarray,
	stem_dir: np.ndarray,
	stem_len: float,
	is_terminal: bool,
	rng: np.random.Generator,
) -> dict | None:
	t_bounds = tuft_sampling_bounds(is_terminal)
	t_param = float(rng.uniform(t_bounds[0], t_bounds[1]))
	r_tuft = float(rng.uniform(t_bounds[2], t_bounds[3]))

	anchor = parent_pos + stem_dir * (stem_len * t_param)
	phi = float(rng.uniform(-math.pi, math.pi))
	z_tuft = float(rng.uniform(-0.045, 0.080))
	tuft_offset = np.array([math.cos(phi) * r_tuft, math.sin(phi) * r_tuft, z_tuft], dtype=np.float64)
	leaf_center = anchor + tuft_offset

	if is_in_subcanopy_pocket(leaf_center):
		return None

	tangent = normalize(np.array([math.cos(phi), math.sin(phi), float(rng.uniform(-0.28, 0.24))], dtype=np.float64))
	tuft_outward = normalize(tuft_offset + np.array([0.0, -0.09, 0.06], dtype=np.float64))
	up_vector = np.array([0.0, 0.0, 1.0], dtype=np.float64)
	tentative_normal = 0.48 * up_vector + 0.52 * tuft_outward + rng.normal(scale=0.36, size=3)
	normal = normalize(tentative_normal - np.dot(tentative_normal, tangent) * tangent)
	if normal[2] < -0.15:
		normal = -normal

	length = round(float(rng.uniform(0.058, 0.082)), 4)
	width = round(length * float(rng.uniform(0.68, 0.80)), 4)
	tone = classify_leaf_tone(leaf_center, rng)

	return {
		'center': [round(float(v), 4) for v in leaf_center],
		'tangent': [round(float(v), 4) for v in tangent],
		'normal': [round(float(v), 4) for v in normal],
		'length': length,
		'width': width,
		'tone': tone,
	}

def is_foliage_bearing_node(node: TreeNode) -> bool:
	if node.is_trunk or node.is_deadwood_stub or node.parent_idx is None:
		return False

	return node.radius < 0.038 and node.pos[2] > 2.00


def generate_foliage_leaves(
	nodes: list[TreeNode],
	rng: np.random.Generator,
) -> list[dict]:
	leaves: list[dict] = []
	foliage_nodes = [n for n in nodes if is_foliage_bearing_node(n)]

	for node in foliage_nodes:
		parent = nodes[node.parent_idx]
		stem_vec = node.pos - parent.pos
		stem_dir = normalize(stem_vec)
		stem_len = float(np.linalg.norm(stem_vec))
		is_terminal = len(node.children) == 0
		count = leaf_count_for_node(node, is_terminal, rng)

		for _ in range(count):
			leaf = sample_single_leaf(parent.pos, stem_dir, stem_len, is_terminal, rng)
			if leaf is not None:
				leaves.append(leaf)

	return leaves


def compute_bounds(branches: list[dict], leaves: list[dict]) -> dict:
	branch_pts = np.array([pt for branch in branches for pt in branch['points']], dtype=np.float64)
	leaf_centers = np.array([lf['center'] for lf in leaves], dtype=np.float64)
	leaf_radii = np.array([lf['length'] * 0.5 for lf in leaves], dtype=np.float64)[:, None]

	b_min = np.min(branch_pts[:, :3] - branch_pts[:, 3:4], axis=0)
	b_max = np.max(branch_pts[:, :3] + branch_pts[:, 3:4], axis=0)
	l_min = np.min(leaf_centers - leaf_radii, axis=0)
	l_max = np.max(leaf_centers + leaf_radii, axis=0)

	min_pt = np.minimum(b_min, l_min)
	max_pt = np.maximum(b_max, l_max)

	return {
		'min': [round(float(v), 4) for v in min_pt],
		'max': [round(float(v), 4) for v in max_pt],
	}

def generate_hero_tree_model(seed: int = SEED, companion: bool = False) -> dict:
	rng = np.random.default_rng(seed)
	nodes: list[TreeNode] = []

	if companion:
		lobes = define_companion_canopy_lobes()
		trunk_indices = build_companion_scaffold(nodes, rng)
	else:
		lobes = define_canopy_lobes()
		trunk_indices = build_deliquescent_scaffold(nodes, rng)

	attractors, attractor_tiers = sample_canopy_attractors(rng, lobes)
	grow_space_colonization(nodes, attractors, attractor_tiers, rng)
	calculate_radii_murray(nodes, trunk_indices)
	branches = decompose_into_branches(nodes)
	leaves = generate_foliage_leaves(nodes, rng)
	bounds = compute_bounds(branches, leaves)

	return {
		'version': 1,
		'seed': seed,
		'branches': branches,
		'leaves': leaves,
		'bounds': bounds,
	}


def resample_branch(pts: np.ndarray, max_step: float = 0.11) -> np.ndarray:
	m_count = len(pts)
	if m_count < 2:
		return pts

	coords = pts[:, :3]
	radii = pts[:, 3:4]

	tangents = np.zeros_like(coords)
	tangents[0] = coords[1] - coords[0]
	tangents[-1] = coords[-1] - coords[-2]
	if m_count > 2:
		tangents[1:-1] = 0.5 * (coords[2:] - coords[:-2])

	new_coords: list[np.ndarray] = []
	new_radii: list[np.ndarray] = []

	for i in range(m_count - 1):
		p0, p1 = coords[i], coords[i + 1]
		r0, r1 = radii[i], radii[i + 1]
		m0, m1 = tangents[i], tangents[i + 1]
		dist = float(np.linalg.norm(p1 - p0))
		steps = max(1, int(math.ceil(dist / max_step)))
		for s in range(steps):
			t = s / steps
			t2 = t * t
			t3 = t2 * t
			pt = (2.0 * t3 - 3.0 * t2 + 1.0) * p0 + (t3 - 2.0 * t2 + t) * m0 + (-2.0 * t3 + 3.0 * t2) * p1 + (t3 - t2) * m1
			new_coords.append(pt)
			new_radii.append((1.0 - t) * r0 + t * r1)

	new_coords.append(coords[-1])
	new_radii.append(radii[-1])
	return np.hstack([np.array(new_coords), np.array(new_radii)])


def compute_parallel_frames(coords: np.ndarray) -> tuple[np.ndarray, list[np.ndarray], list[np.ndarray]]:
	num_points = len(coords)
	tangents = np.zeros_like(coords)
	tangents[0] = coords[1] - coords[0]
	tangents[-1] = coords[-1] - coords[-2]
	if num_points > 2:
		tangents[1:-1] = coords[2:] - coords[:-2]

	t_norms = np.linalg.norm(tangents, axis=1, keepdims=True)
	t_norms[t_norms < 1e-9] = 1.0
	tangents /= t_norms

	t0 = tangents[0]
	if abs(t0[2]) < 0.9:
		up = np.array([0.0, 0.0, 1.0])
	else:
		up = np.array([1.0, 0.0, 0.0])

	n0 = np.cross(t0, up)
	n0 /= np.linalg.norm(n0)

	normals = [n0]
	binormals = [np.cross(t0, n0)]

	for i in range(num_points - 1):
		prev_t, curr_t = tangents[i], tangents[i + 1]
		axis = np.cross(prev_t, curr_t)
		axis_norm = float(np.linalg.norm(axis))
		if axis_norm < 1e-6:
			next_n = normals[-1]
		else:
			axis /= axis_norm
			angle = math.acos(float(np.clip(np.dot(prev_t, curr_t), -1.0, 1.0)))
			n_last = normals[-1]
			rot = n_last * math.cos(angle) + np.cross(axis, n_last) * math.sin(angle) + axis * np.dot(axis, n_last) * (1.0 - math.cos(angle))
			rot -= curr_t * np.dot(rot, curr_t)
			next_n = rot / np.linalg.norm(rot)

		normals.append(next_n)
		binormals.append(np.cross(curr_t, next_n))

	return tangents, normals, binormals


def build_tube_side_and_cap_faces(
	start_v: int,
	num_points: int,
	k_rings: int,
	tip_idx: int,
	root_idx: int,
) -> list[tuple[int, ...]]:
	faces: list[tuple[int, ...]] = []
	for i in range(num_points - 1):
		r1 = start_v + i * k_rings
		r2 = start_v + (i + 1) * k_rings
		for k in range(k_rings):
			kn = (k + 1) % k_rings
			faces.append((r1 + k, r2 + k, r2 + kn, r1 + kn))

	r_last = start_v + (num_points - 1) * k_rings
	for k in range(k_rings):
		kn = (k + 1) % k_rings
		faces.append((r_last + k, tip_idx, r_last + kn))
		faces.append((root_idx, start_v + kn, start_v + k))

	return faces


def append_tube_geometry(
	points: np.ndarray,
	ring: RingFrame,
	branch_verts: list[list[float]],
	branch_faces: list[tuple[int, ...]],
) -> None:
	num_points = len(points)
	if num_points < 2:
		return

	start_v = len(branch_verts)
	coords = points[:, :3]
	radii = points[:, 3]
	tangents, normals, binormals = compute_parallel_frames(coords)

	for i in range(num_points):
		flutes = 1.0 + 0.04 * np.cos(3.0 * ring.angles) + 0.025 * np.sin(5.0 * ring.angles)
		offsets = radii[i] * flutes[:, None] * (ring.cos_a[:, None] * normals[i] + ring.sin_a[:, None] * binormals[i])
		ring_pts = coords[i] + offsets
		branch_verts.extend(ring_pts.tolist())

	tip_idx = len(branch_verts)
	branch_verts.append((coords[-1] + tangents[-1] * radii[-1]).tolist())
	root_idx = len(branch_verts)
	branch_verts.append(coords[0].copy().tolist())
	branch_faces.extend(build_tube_side_and_cap_faces(start_v, num_points, ring.k_rings, tip_idx, root_idx))

def smooth_trunk_control_points(raw_points: np.ndarray) -> np.ndarray:
	smoothed = raw_points.copy()
	for _ in range(2):
		smoothed[1:-1, :3] = 0.22 * smoothed[:-2, :3] + 0.56 * smoothed[1:-1, :3] + 0.22 * smoothed[2:, :3]
		smoothed[1:-1, 3] = 0.25 * smoothed[:-2, 3] + 0.50 * smoothed[1:-1, 3] + 0.25 * smoothed[2:, 3]

	return smoothed


def create_remeshed_heavy_object(
	heavy_verts: list[list[float]],
	heavy_faces: list[tuple[int, ...]],
	bark_mat: bpy.types.Material,
) -> None:
	heavy_mesh = bpy.data.meshes.new('HeavyBranchMesh')
	heavy_mesh.from_pydata(heavy_verts, [], heavy_faces)
	heavy_mesh.update()
	heavy_mesh.polygons.foreach_set('use_smooth', [True] * len(heavy_mesh.polygons))

	heavy_obj = bpy.data.objects.new('HeavyBranches', heavy_mesh)
	bpy.context.scene.collection.objects.link(heavy_obj)

	remesh_mod = heavy_obj.modifiers.new(name='Remesh', type='REMESH')
	remesh_mod.mode = 'VOXEL'
	remesh_mod.voxel_size = 0.0065
	bpy.context.view_layer.objects.active = heavy_obj
	bpy.ops.object.modifier_apply(modifier='Remesh')

	smooth_mod = heavy_obj.modifiers.new(name='Smooth', type='SMOOTH')
	smooth_mod.factor = 0.55
	smooth_mod.iterations = 3
	bpy.ops.object.modifier_apply(modifier='Smooth')
	heavy_obj.data.polygons.foreach_set('use_smooth', [True] * len(heavy_obj.data.polygons))
	heavy_obj.data.materials.append(bark_mat)


def create_twig_object(
	twig_verts: list[list[float]],
	twig_faces: list[tuple[int, ...]],
	bark_mat: bpy.types.Material,
) -> None:
	if not twig_verts:
		return

	twig_mesh = bpy.data.meshes.new('FineTwigMesh')
	twig_mesh.from_pydata(twig_verts, [], twig_faces)
	twig_mesh.update()
	twig_mesh.polygons.foreach_set('use_smooth', [True] * len(twig_mesh.polygons))

	twig_obj = bpy.data.objects.new('FineTwigs', twig_mesh)
	bpy.context.scene.collection.objects.link(twig_obj)
	twig_obj.data.materials.append(bark_mat)


def resample_single_branch(branch: dict) -> np.ndarray:
	raw_points = np.array(branch['points'], dtype=np.float64)
	if branch.get('parent') is None and len(raw_points) >= 3:
		raw_points = smooth_trunk_control_points(raw_points)

	return resample_branch(raw_points, max_step=0.11)


def build_branch_meshes(branches: list[dict], bark_mat: bpy.types.Material) -> None:
	heavy_verts: list[list[float]] = []
	heavy_faces: list[tuple[int, ...]] = []
	twig_verts: list[list[float]] = []
	twig_faces: list[tuple[int, ...]] = []

	heavy_ring = RingFrame.create(14)
	twig_ring = RingFrame.create(10)

	for branch in branches:
		points = resample_single_branch(branch)
		append_tube_geometry(points[points[:, 3] >= 0.010], heavy_ring, heavy_verts, heavy_faces)
		append_tube_geometry(points[points[:, 3] <= 0.019], twig_ring, twig_verts, twig_faces)

	if heavy_verts:
		create_remeshed_heavy_object(heavy_verts, heavy_faces, bark_mat)

	create_twig_object(twig_verts, twig_faces, bark_mat)

def build_leaf_mesh(leaves: list[dict]) -> tuple[bpy.types.Object, list[int]]:
	leaf_verts: list[list[float]] = []
	leaf_faces: list[tuple[int, ...]] = []
	leaf_tones: list[int] = []

	v_offset = 0
	for leaf in leaves:
		center = np.array(leaf['center'], dtype=np.float64)
		tangent = normalize(np.array(leaf['tangent'], dtype=np.float64))
		raw_normal = np.array(leaf['normal'], dtype=np.float64)
		normal = normalize(raw_normal - tangent * np.dot(raw_normal, tangent))
		binormal = np.cross(tangent, normal)

		leaf_length = float(leaf.get('length', 0.068))
		leaf_width = float(leaf.get('width', 0.050))
		tone = int(leaf.get('tone', 2))

		v0 = center - 0.5 * leaf_length * tangent
		v1 = center + 0.04 * leaf_length * tangent + 0.5 * leaf_width * binormal - 0.08 * leaf_width * normal
		v2 = center + 0.5 * leaf_length * tangent
		v3 = center + 0.04 * leaf_length * tangent - 0.5 * leaf_width * binormal - 0.08 * leaf_width * normal
		v4 = center + 0.04 * leaf_length * tangent + 0.10 * leaf_width * normal

		idx = v_offset
		leaf_verts.extend([v0.tolist(), v1.tolist(), v2.tolist(), v3.tolist(), v4.tolist()])
		v_offset += 5

		leaf_faces.extend([
			(idx + 0, idx + 1, idx + 4),
			(idx + 4, idx + 1, idx + 2),
			(idx + 0, idx + 4, idx + 3),
			(idx + 4, idx + 3, idx + 2),
		])

		leaf_tones.extend([tone, tone, tone, tone])

	mesh = bpy.data.meshes.new('LeafMesh')
	mesh.from_pydata(leaf_verts, [], leaf_faces)
	mesh.update()
	mesh.polygons.foreach_set('use_smooth', [True] * len(mesh.polygons))

	obj = bpy.data.objects.new('Leaves', mesh)
	bpy.context.scene.collection.objects.link(obj)
	return obj, leaf_tones


def create_bark_material() -> bpy.types.Material:
	mat = bpy.data.materials.new('BarkMaterial')
	nodes = mat.node_tree.nodes
	links = mat.node_tree.links
	nodes.clear()

	output = nodes.new('ShaderNodeOutputMaterial')
	principled = nodes.new('ShaderNodeBsdfPrincipled')
	bump = nodes.new('ShaderNodeBump')
	tex_coord = nodes.new('ShaderNodeTexCoord')
	mapping_vert = nodes.new('ShaderNodeMapping')
	mapping_horiz = nodes.new('ShaderNodeMapping')
	mix_vec = nodes.new('ShaderNodeMix')
	mix_vec.data_type = 'VECTOR'
	noise_grain = nodes.new('ShaderNodeTexNoise')
	voronoi = nodes.new('ShaderNodeTexVoronoi')
	math_add = nodes.new('ShaderNodeMath')
	color_ramp = nodes.new('ShaderNodeValToRGB')

	mapping_vert.inputs['Scale'].default_value = (16.0, 16.0, 1.2)
	mapping_horiz.inputs['Scale'].default_value = (1.2, 16.0, 16.0)

	sep_norm = nodes.new('ShaderNodeSeparateXYZ')
	dot_math = nodes.new('ShaderNodeMath')
	dot_math.operation = 'MULTIPLY'
	dot_clamp = nodes.new('ShaderNodeMath')
	dot_clamp.operation = 'MULTIPLY'

	links.new(tex_coord.outputs['Normal'], sep_norm.inputs['Vector'])
	links.new(sep_norm.outputs['Z'], dot_math.inputs[0])
	links.new(sep_norm.outputs['Z'], dot_math.inputs[1])
	links.new(dot_math.outputs['Value'], dot_clamp.inputs[0])
	dot_clamp.inputs[1].default_value = 3.0

	links.new(tex_coord.outputs['Object'], mapping_vert.inputs['Vector'])
	links.new(tex_coord.outputs['Object'], mapping_horiz.inputs['Vector'])
	links.new(dot_clamp.outputs['Value'], mix_vec.inputs[0])
	links.new(mapping_vert.outputs['Vector'], mix_vec.inputs[4])
	links.new(mapping_horiz.outputs['Vector'], mix_vec.inputs[5])

	voronoi.feature = 'DISTANCE_TO_EDGE'
	voronoi.inputs['Scale'].default_value = 5.0
	links.new(mix_vec.outputs[1], voronoi.inputs['Vector'])

	noise_grain.inputs['Scale'].default_value = 10.0
	noise_grain.inputs['Detail'].default_value = 6.0
	noise_grain.inputs['Roughness'].default_value = 0.60
	links.new(mix_vec.outputs[1], noise_grain.inputs['Vector'])

	math_add.operation = 'ADD'
	links.new(voronoi.outputs['Distance'], math_add.inputs[0])
	links.new(noise_grain.outputs['Fac'], math_add.inputs[1])

	ramp = color_ramp.color_ramp
	ramp.elements[0].position = 0.20
	ramp.elements[0].color = (0.016, 0.011, 0.008, 1.0)
	ramp.elements[1].position = 0.55
	ramp.elements[1].color = (0.036, 0.026, 0.018, 1.0)
	elem2 = ramp.elements.new(0.85)
	elem2.color = (0.052, 0.038, 0.027, 1.0)

	links.new(math_add.outputs['Value'], color_ramp.inputs['Fac'])
	links.new(color_ramp.outputs['Color'], principled.inputs['Base Color'])

	bump.inputs['Distance'].default_value = 0.022
	bump.inputs['Strength'].default_value = 0.85
	links.new(math_add.outputs['Value'], bump.inputs['Height'])
	links.new(bump.outputs['Normal'], principled.inputs['Normal'])

	principled.inputs['Roughness'].default_value = 0.88
	principled.inputs['Specular IOR Level'].default_value = 0.14
	links.new(principled.outputs['BSDF'], output.inputs['Surface'])

	return mat


def create_leaf_material(tone: int) -> bpy.types.Material:
	mat = bpy.data.materials.new(f'LeafMaterial_Tone{tone}')
	nodes = mat.node_tree.nodes
	links = mat.node_tree.links
	nodes.clear()

	output = nodes.new('ShaderNodeOutputMaterial')
	mix = nodes.new('ShaderNodeMixShader')
	principled = nodes.new('ShaderNodeBsdfPrincipled')
	translucent = nodes.new('ShaderNodeBsdfTranslucent')

	palette = [
		((0.020, 0.052, 0.016, 1.0), (0.042, 0.098, 0.024, 1.0), 0.24),
		((0.032, 0.078, 0.022, 1.0), (0.068, 0.142, 0.034, 1.0), 0.28),
		((0.048, 0.105, 0.028, 1.0), (0.095, 0.185, 0.044, 1.0), 0.32),
		((0.066, 0.132, 0.034, 1.0), (0.125, 0.225, 0.054, 1.0), 0.35),
		((0.088, 0.162, 0.040, 1.0), (0.160, 0.268, 0.064, 1.0), 0.38),
	]

	base_color, trans_color, trans_fac = palette[min(4, max(0, tone))]

	principled.inputs['Base Color'].default_value = base_color
	principled.inputs['Roughness'].default_value = 0.58
	principled.inputs['Specular IOR Level'].default_value = 0.12
	translucent.inputs['Color'].default_value = trans_color
	mix.inputs['Fac'].default_value = trans_fac

	links.new(principled.outputs['BSDF'], mix.inputs[1])
	links.new(translucent.outputs['BSDF'], mix.inputs[2])
	links.new(mix.outputs['Shader'], output.inputs['Surface'])

	return mat


def setup_lighting() -> None:
	key_light_data = bpy.data.lights.new(name='KeySun', type='SUN')
	key_light_data.energy = 4.8
	key_light_data.angle = math.radians(4.5)
	key_light_data.color = (1.0, 0.97, 0.91)
	key_light = bpy.data.objects.new('KeySun', key_light_data)
	key_light.rotation_euler = (math.radians(44.0), math.radians(14.0), math.radians(-36.0))
	bpy.context.scene.collection.objects.link(key_light)

	rim_light_data = bpy.data.lights.new(name='RimSun', type='SUN')
	rim_light_data.energy = 3.8
	rim_light_data.angle = math.radians(5.5)
	rim_light_data.color = (1.0, 0.92, 0.82)
	rim_light = bpy.data.objects.new('RimSun', rim_light_data)
	rim_light.rotation_euler = (math.radians(-46.0), math.radians(-12.0), math.radians(142.0))
	bpy.context.scene.collection.objects.link(rim_light)

	world = bpy.data.worlds.new('TreeWorld')
	bpy.context.scene.world = world
	world_nodes = world.node_tree.nodes
	world_links = world.node_tree.links
	world_nodes.clear()
	w_output = world_nodes.new('ShaderNodeOutputWorld')
	w_bg = world_nodes.new('ShaderNodeBackground')
	w_bg.inputs['Color'].default_value = (0.76, 0.84, 0.93, 1.0)
	w_bg.inputs['Strength'].default_value = 0.58
	world_links.new(w_bg.outputs['Background'], w_output.inputs['Surface'])


def setup_camera(bounds: dict, fill_ratio: float = 0.86, focal_length: float = 95.0) -> None:
	b_min = np.array(bounds['min'])
	b_max = np.array(bounds['max'])
	center = 0.5 * (b_min + b_max)
	width = b_max[0] - b_min[0]
	depth = b_max[1] - b_min[1]
	height = b_max[2] - b_min[2]

	cam_data = bpy.data.cameras.new('RenderCamera')
	cam_obj = bpy.data.objects.new('RenderCamera', cam_data)
	bpy.context.scene.collection.objects.link(cam_obj)
	bpy.context.scene.camera = cam_obj

	cam_data.sensor_width = 36.0
	cam_data.sensor_height = 36.0
	cam_data.sensor_fit = 'AUTO'
	cam_data.type = 'PERSP'
	cam_data.lens = focal_length

	target_span = max(width, height) / fill_ratio
	fov = 2.0 * math.atan((cam_data.sensor_width / 2.0) / focal_length)
	camera_dist = (target_span / 2.0) / math.tan(fov / 2.0) + depth * 0.5

	cam_obj.location = (center[0], b_min[1] - camera_dist, center[2])
	direction = Vector(center) - Vector(cam_obj.location)
	cam_obj.rotation_euler = direction.to_track_quat('-Z', 'Y').to_euler()


def verify_transparent_borders(img: Image.Image) -> tuple[int, int, int, int]:
	alpha = img.getchannel('A')
	edge_boxes = (
		alpha.crop((0, 0, img.width, 1)).getbbox(),
		alpha.crop((0, img.height - 1, img.width, img.height)).getbbox(),
		alpha.crop((0, 0, 1, img.height)).getbbox(),
		alpha.crop((img.width - 1, 0, img.width, img.height)).getbbox(),
	)

	if any(box is not None for box in edge_boxes):
		raise RuntimeError('Rendered tree clips an image border')

	bounds = alpha.getbbox()
	if bounds is None:
		raise RuntimeError('Rendered tree image is empty')

	return bounds


def render_tree(output_path: Path, companion: bool) -> None:
	output_path.parent.mkdir(parents=True, exist_ok=True)
	if companion:
		seed = COMPANION_SEED
	else:
		seed = SEED

	model = generate_hero_tree_model(seed=seed, companion=companion)

	bpy.ops.wm.read_factory_settings(use_empty=True)
	scene = bpy.context.scene
	scene.render.engine = 'CYCLES'
	scene.cycles.device = 'CPU'
	scene.cycles.samples = RENDER_SAMPLES
	scene.cycles.seed = seed
	scene.cycles.use_animated_seed = False
	scene.cycles.use_adaptive_sampling = True
	scene.render.threads_mode = 'AUTO'
	scene.render.resolution_x = RENDER_RESOLUTION
	scene.render.resolution_y = RENDER_RESOLUTION
	scene.render.resolution_percentage = 100
	scene.render.film_transparent = True
	scene.render.image_settings.file_format = 'PNG'
	scene.render.image_settings.color_mode = 'RGBA'

	bark_mat = create_bark_material()
	build_branch_meshes(model['branches'], bark_mat)

	leaves_obj, leaf_tones = build_leaf_mesh(model['leaves'])
	leaf_mats = [create_leaf_material(tone=t) for t in range(5)]
	for mat in leaf_mats:
		leaves_obj.data.materials.append(mat)

	leaves_obj.data.polygons.foreach_set('material_index', leaf_tones)

	setup_lighting()
	setup_camera(model['bounds'])

	with TemporaryDirectory() as tmp_dir:
		raw_render_path = Path(tmp_dir) / 'raw_hero_tree.png'
		scene.render.filepath = str(raw_render_path)
		bpy.ops.render.render(write_still=True)

		raw_img = Image.open(raw_render_path).convert('RGBA')
		bbox = verify_transparent_borders(raw_img)

		crop_box = (
			max(0, bbox[0] - TRANSPARENT_PADDING),
			max(0, bbox[1] - TRANSPARENT_PADDING),
			min(raw_img.width, bbox[2] + TRANSPARENT_PADDING),
			min(raw_img.height, bbox[3] + TRANSPARENT_PADDING),
		)

		cropped = raw_img.crop(crop_box)
		target_height = round(cropped.height * (SPRITE_WIDTH / cropped.width))
		sprite = cropped.resize((SPRITE_WIDTH, target_height), Image.Resampling.LANCZOS)
		verify_transparent_borders(sprite)
		sprite.save(output_path, optimize=True)

	alpha = np.asarray(sprite, dtype=np.float32)[:, :, 3] / 255.0
	cov = 100.0 * float((alpha > 0.5).mean())
	print(f'Saved {output_path.name}: {sprite.width}x{sprite.height}, {output_path.stat().st_size} bytes, coverage={cov:.2f}%')


def main() -> None:
	parser = argparse.ArgumentParser(description=__doc__)
	parser.add_argument('--variant', choices=('all', 'hero', 'companion'), default='all')
	args = parser.parse_args()
	if args.variant in ('all', 'hero'):
		render_tree(OUTPUT_PATH, companion=False)

	if args.variant in ('all', 'companion'):
		render_tree(COMPANION_OUTPUT_PATH, companion=True)


if __name__ == '__main__':
	main()
