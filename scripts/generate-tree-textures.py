# /// script
# requires-python = ">=3.12"
# dependencies = ["numpy==2.5.3", "pillow==12.3.0"]
# ///
"""Generate reproducible first-party conifer tree textures for Weatherd foreground layer."""

# Generates three distinct detailed evergreen tree sprites for portrait foreground composition.
# No photographs or third-party artwork are sampled.

import math
from dataclasses import dataclass
from functools import partial
from pathlib import Path
from random import Random

import numpy as np
from PIL import Image, ImageDraw

CANVAS_WIDTH = 512
CANVAS_HEIGHT = 1024

OUTPUT_DIR = Path(__file__).resolve().parents[1] / 'app/src/main/res/drawable-nodpi'

GOLDEN_ANGLE = 2.399963229728653


def clamp(val, low, high):
	return max(low, min(high, val))


def light_to_color(intensity):
	"""Convert illumination intensity to spruce needle color."""
	if intensity > 0.68:
		# Golden sunlit highlight tips.
		u = clamp((intensity - 0.68) / 0.32, 0.0, 1.0)
		return (int(152 + 58 * u), int(186 + 38 * u), int(56 + 36 * u))

	if intensity > 0.42:
		# Sunlit warm olive green.
		u = (intensity - 0.42) / 0.26
		return (int(74 + 78 * u), int(120 + 66 * u), int(30 + 26 * u))

	if intensity > 0.22:
		# Rich evergreen midtone.
		u = (intensity - 0.22) / 0.20
		return (int(32 + 42 * u), int(62 + 58 * u), int(18 + 12 * u))

	# Deep forest shadow.
	u = intensity / 0.22
	return (int(14 + 18 * u), int(26 + 36 * u), int(10 + 8 * u))


@dataclass(frozen=True)
class ConiferConfig:
	"""Configuration parameters for a distinct conifer morphology."""
	seed: int
	apex_y: float
	base_width: float
	envelope_power: float
	num_whorls: int
	num_internodals: int
	trunk_bend: float
	droop_factor: float
	asymmetry: float
	bark_color: tuple[int, int, int]


@dataclass(frozen=True)
class BranchGeometry:
	"""A curved bough with painter-order depth and an independent foliage seed."""
	points: list[tuple[float, float, float]]
	reach: float
	tier: float
	depth: float
	seed: int


def trunk_x(config, y):
	"""Locate the organically bent trunk at a sprite height."""
	t = (y - config.apex_y) / (float(CANVAS_HEIGHT) - config.apex_y)
	bend = config.trunk_bend
	return CANVAS_WIDTH * 0.5 + bend * 4.5 * math.sin(t * 2.5 + 0.3) + bend * 1.5 * math.sin(t * 6.2)


def trunk_radius(config, y):
	"""Taper the trunk toward the apex."""
	t = (y - config.apex_y) / (float(CANVAS_HEIGHT) - config.apex_y)
	return 1.8 + (config.base_width * 0.082) * (t ** 0.88)


def draw_trunk_segment(draw, config, x0, y0, x1, y1, r0, r1):
	"""Paint one tapered bark segment and its vertical light strips."""
	base_c = config.bark_color
	draw.polygon([(x0 - r0, y0), (x0 + r0, y0), (x1 + r1, y1), (x1 - r1, y1)], fill=(*base_c, 255))
	for strip in np.linspace(-0.85, 0.85, 6):
		shade = 1.0 - abs(strip) * 0.45
		color = (int(base_c[0] * 1.5 * shade), int(base_c[1] * 1.5 * shade), int(base_c[2] * 1.5 * shade), 255)
		draw.line([(x0 + strip * r0, y0), (x1 + strip * r1, y1)], fill=color, width=max(1, int(r0 * 0.22)))


def trunk_commands(config):
	"""Prepare bark strokes without changing their stable painter order."""
	commands = []
	y_steps = np.linspace(config.apex_y, float(CANVAS_HEIGHT), 160)
	for index in range(len(y_steps) - 1):
		y0, y1 = y_steps[index], y_steps[index + 1]
		command = partial(
			draw_trunk_segment,
			config=config,
			x0=trunk_x(config, y0),
			y0=y0,
			x1=trunk_x(config, y1),
			y1=y1,
			r0=trunk_radius(config, y0),
			r1=trunk_radius(config, y1)
		)

		commands.append((0.0, command))

	return commands


def draw_apex(draw, config, seed):
	"""Paint the leader shoot with its own bristling needle population."""
	rng = Random(seed)  # nosec B311 - deterministic artwork generation, not security-sensitive randomness.
	points = [(trunk_x(config, y), y) for y in np.linspace(config.apex_y - 20.0, config.apex_y + 35.0, 25)]
	draw.line(points, fill=(*config.bark_color, 255), width=2)
	for px, py in points:
		for side in [-1.0, 1.0]:
			for _ in range(2):
				length = rng.uniform(4.0, 7.5)
				angle = rng.uniform(0.35, 1.15)
				ex = px + side * math.sin(angle) * length
				ey = py - math.cos(angle) * length
				draw.line([(px, py), (ex, ey)], fill=(125, 160, 52, 255), width=1)


def branch_population(config, rng):
	"""Seed major whorls and internodal filler before drawing any bough."""
	tree_height = float(CANVAS_HEIGHT) - config.apex_y
	branches = []
	for whorl in range(config.num_whorls):
		t_whorl = ((whorl + 1) / (config.num_whorls + 1)) ** 1.04
		count = 4 if t_whorl < 0.18 else (5 if t_whorl < 0.55 else 6)
		base_rotation = whorl * GOLDEN_ANGLE + rng.uniform(-0.20, 0.20)
		for index in range(count):
			y_jitter = rng.uniform(-4.0, 4.0)
			y_origin = config.apex_y + t_whorl * tree_height + y_jitter
			tier = clamp((y_origin - config.apex_y) / tree_height, 0.02, 0.98)
			angle = base_rotation + index * (2 * math.pi / count) + rng.uniform(-0.20, 0.20)
			branches.append((y_origin, tier, angle, True))

	for _ in range(config.num_internodals):
		tier = rng.uniform(0.08, 0.96)
		y_origin = config.apex_y + tier * tree_height
		angle = rng.uniform(0, 2 * math.pi)
		branches.append((y_origin, tier, angle, False))

	return branches


def branch_geometry(config, rng, specification):
	"""Curve one asymmetric bough while preserving population RNG consumption."""
	y_origin, tier, angle, is_major = specification
	tx = trunk_x(config, y_origin)
	cone_width = config.base_width * (tier ** config.envelope_power)
	if is_major:
		tier_jitter = rng.uniform(0.88, 1.15)
		lateral_boost = 1.0 + 0.15 * (abs(math.cos(angle)) ** 1.2)
		reach = cone_width * tier_jitter * lateral_boost
	else:
		reach = cone_width * rng.uniform(0.45, 0.72)

	if math.cos(angle) < 0:
		reach *= 1.0 + config.asymmetry
	else:
		reach *= 1.0 - config.asymmetry

	if reach < 6.0:
		return None

	cos_a, sin_a = math.cos(angle), math.sin(angle)
	elevation = (0.42 - 0.78 * tier) * 0.65 + rng.uniform(-0.06, 0.06)
	steps = max(5, int(reach / 10.0))
	points = []
	depth_sum = 0.0
	for sample in range(steps + 1):
		fraction = sample / steps
		distance = fraction * reach
		droop = (config.droop_factor * (tier ** 1.25) + 3.0) * (fraction ** 1.9)
		curl = 9.0 * (max(0.0, fraction - 0.65) / 0.35) ** 1.8 if fraction > 0.65 else 0.0
		wander_x = 4.0 * math.sin(fraction * math.pi * 1.5 + angle)
		x = tx + distance * cos_a + wander_x
		z = distance * sin_a
		y = y_origin - distance * math.sin(elevation) + droop - curl
		points.append((x, y, z))
		depth_sum += z

	return BranchGeometry(points, reach, tier, depth_sum / (steps + 1), rng.randint(0, 1000000))


def twig_clusters(draw, config, rng, branch, point, tangent, maximum_length):
	"""Paint lateral twigs and return their pendulous foliage clusters."""
	tdx, tdy = tangent
	tnx, tny = -tdy, tdx
	clusters = []
	for side in [-1.0, 1.0]:
		if rng.random() < 0.10:
			continue

		length = maximum_length * rng.uniform(0.7, 1.2)
		if length < 4.0:
			continue

		angle = rng.uniform(0.55, 0.80)
		dx = tdx * math.cos(angle) + tnx * side * math.sin(angle)
		dy = tdy * math.cos(angle) + tny * side * math.sin(angle) + 0.35 * (branch.tier ** 0.5)
		norm = math.hypot(dx, dy) + 1e-5
		dx /= norm
		dy /= norm
		end_x = point[0] + dx * length
		end_y = point[1] + dy * length + 4.0 * (branch.tier ** 1.3)
		draw.line([(point[0], point[1]), (end_x, end_y)], fill=(*config.bark_color, 255), width=1)
		for fraction in [0.35, 0.70, 1.0]:
			x = point[0] + (end_x - point[0]) * fraction
			y = point[1] + (end_y - point[1]) * fraction
			radius = max(6.5, length * 0.46 * (0.85 if fraction < 1.0 else 1.0))
			clusters.append(((x, y), (dx, dy), radius))

	return clusters


def foliage_clusters(draw, config, rng, branch):
	"""Place needle sprays along the main bough and its lateral twigs."""
	clusters = []
	points = branch.points
	for index in range(1, len(points)):
		fraction = index / len(points)
		if fraction < 0.08:
			continue

		point = points[index]
		previous = points[index - 1]
		following = points[min(index + 1, len(points) - 1)]
		dx = following[0] - previous[0]
		dy = following[1] - previous[1]
		norm = math.hypot(dx, dy) + 1e-5
		tangent = (dx / norm, dy / norm)
		maximum_length = branch.reach * 0.38 * (math.sin(fraction * math.pi) ** 0.68)
		clusters.extend(twig_clusters(draw, config, rng, branch, point, tangent, maximum_length))
		if rng.random() < 0.75:
			clusters.append(((point[0], point[1]), tangent, branch.reach * 0.14))

	return clusters


def draw_shadow_needles(draw, rng, clusters, branch):
	"""Lay down dense shadow needles before painting lit needle tips."""
	depth_factor = clamp(0.35 + 0.65 * ((branch.depth + 100.0) / 200.0), 0.22, 1.0)
	tier_factor = clamp(0.35 + 0.65 * (1.0 - branch.tier * 0.50), 0.28, 1.0)
	for center, (dx, dy), radius in clusters:
		color = (int(13 * depth_factor * tier_factor + 3), int(25 * depth_factor * tier_factor + 5), int(11 * depth_factor * tier_factor + 3))
		base_angle = math.atan2(dy, dx)
		for _ in range(int(radius * 1.8)):
			offset = rng.uniform(-1.3, 1.3)
			angle = base_angle + offset
			ndx, ndy = math.cos(angle), math.sin(angle)
			length = rng.uniform(radius * 0.4, radius * 0.85)
			x = center[0] + rng.uniform(-2.5, 2.5)
			y = center[1] + rng.uniform(-2.5, 2.5)
			end_x = x + ndx * length
			end_y = y + ndy * length
			draw.line([(x, y), (end_x, end_y)], fill=(*color, 225), width=max(1, int(rng.choice([1, 2]))))


def draw_detail_needles(draw, rng, clusters, branch, light):
	"""Paint lit needles with the unchanged depth and base-to-tip color ramp."""
	lx, ly, lz = light
	for center, (dx, dy), radius in clusters:
		base_angle = math.atan2(dy, dx)
		for _ in range(int(radius * 2.2)):
			offset = rng.uniform(-1.25, 1.25)
			angle = base_angle + offset
			ndx, ndy = math.cos(angle), math.sin(angle)
			length = rng.uniform(5.5, 10.0)
			x = center[0] + rng.uniform(-3.0, 3.0)
			y = center[1] + rng.uniform(-2.5, 2.5)
			mid_x = x + ndx * (length * 0.48)
			mid_y = y + ndy * (length * 0.48)
			tip_x = x + ndx * length
			tip_y = y + ndy * length
			norm_x = ndx
			norm_y = ndy - 0.45
			norm_z = 0.4 if branch.depth > 0 else -0.4
			norm = math.hypot(norm_x, norm_y, norm_z) + 1e-5
			norm_x /= norm
			norm_y /= norm
			norm_z /= norm
			dot_light = max(0.0, norm_x * lx + norm_y * ly + norm_z * lz)
			ambient_tier = 0.35 + 0.65 * (1.0 - branch.tier * 0.42)
			ambient_depth = clamp(0.4 + 0.6 * ((branch.depth + 80.0) / 160.0), 0.22, 1.0)
			top_bias = 1.0 if ndy < 0 else 0.52
			light_tip = (0.26 + 0.74 * dot_light) * ambient_tier * ambient_depth * top_bias
			light_base = light_tip * 0.42
			base_color = light_to_color(light_base)
			tip_color = light_to_color(light_tip)
			draw.line([(x, y), (mid_x, mid_y)], fill=(*base_color, 255), width=1)
			draw.line([(mid_x, mid_y), (tip_x, tip_y)], fill=(*tip_color, 255), width=1)


def draw_branch(draw, config, branch, light):
	"""Paint one bough in stable wood, shadow, then sunlit-needle order."""
	rng = Random(branch.seed)  # nosec B311 - deterministic artwork generation, not security-sensitive randomness.
	stem = [(point[0], point[1]) for point in branch.points]
	stem_width = max(1, int(3.0 * (1.0 - 0.6 * branch.tier)))
	draw.line(stem, fill=(*config.bark_color, 255), width=stem_width)
	clusters = foliage_clusters(draw, config, rng, branch)
	draw_shadow_needles(draw, rng, clusters, branch)
	draw_detail_needles(draw, rng, clusters, branch, light)


def generate_tree(config):
	"""Generate a single conifer sprite image with the approved fixed-seed layout."""
	rng = Random(config.seed)  # nosec B311 - deterministic artwork generation, not security-sensitive randomness.
	image = Image.new('RGBA', (CANVAS_WIDTH, CANVAS_HEIGHT), (0, 0, 0, 0))
	draw = ImageDraw.Draw(image)
	direction = (-0.24, -0.92, 0.28)
	magnitude = math.hypot(direction[0], direction[1], direction[2])
	light = (direction[0] / magnitude, direction[1] / magnitude, direction[2] / magnitude)
	commands = trunk_commands(config)
	commands.append((0.0, partial(draw_apex, config=config, seed=rng.randint(0, 1000000))))
	for specification in branch_population(config, rng):
		branch = branch_geometry(config, rng, specification)
		if branch is not None:
			commands.append((branch.depth, partial(draw_branch, config=config, branch=branch, light=light)))

	commands.sort(key=lambda item: item[0])
	for _, command in commands:
		command(draw)

	return image


def main():
	OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

	tree_configs = [
		# Tree 1: Dominant hero evergreen - broad base, dense full boughs, majestic presence.
		ConiferConfig(
			seed=1001,
			apex_y=50.0,
			base_width=176.0,
			envelope_power=0.90,
			num_whorls=32,
			num_internodals=75,
			trunk_bend=0.8,
			droop_factor=38.0,
			asymmetry=0.02,
			bark_color=(44, 28, 18),
		),
		# Tree 2: Slender / taller open conifer - narrower profile, spaced whorls, visible trunk.
		ConiferConfig(
			seed=2002,
			apex_y=42.0,
			base_width=155.0,
			envelope_power=0.96,
			num_whorls=28,
			num_internodals=50,
			trunk_bend=0.5,
			droop_factor=28.0,
			asymmetry=-0.02,
			bark_color=(48, 32, 20),
		),
		# Tree 3: Compact / character supporting spruce - distinct asymmetric habit.
		ConiferConfig(
			seed=3003,
			apex_y=66.0,
			base_width=162.0,
			envelope_power=0.88,
			num_whorls=29,
			num_internodals=65,
			trunk_bend=-0.6,
			droop_factor=34.0,
			asymmetry=0.06,
			bark_color=(42, 26, 16),
		),
	]

	for idx, cfg in enumerate(tree_configs, start=1):
		print(f'Generating tree_evergreen_{idx}...')
		img = generate_tree(cfg)
		out_path = OUTPUT_DIR / f'tree_evergreen_{idx}.png'
		img.save(out_path, optimize=True)
		alpha = np.asarray(img, dtype=np.float32)[:, :, 3] / 255.0
		cov = 100 * (alpha > 0.5).mean()
		print(f'Saved {out_path.name}: {img.width}x{img.height}, {out_path.stat().st_size} bytes, coverage={cov:.2f}%')


if __name__ == '__main__':
	main()
