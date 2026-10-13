# /// script
# requires-python = ">=3.12"
# dependencies = ["numpy==2.5.3", "pillow==12.3.0"]
# ///
"""Generate reproducible first-party conifer tree textures for Weatherd foreground layer."""

# Generates three distinct detailed evergreen tree sprites for portrait foreground composition.
# No photographs or third-party artwork are sampled.

import math
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


class ConiferConfig:
	"""Configuration parameters for a distinct conifer morphology."""

	def __init__(self, seed, apex_y, base_width, envelope_power, num_whorls, num_internodals, trunk_bend, droop_factor, asymmetry, bark_color):
		self.seed = seed
		self.apex_y = apex_y
		self.base_width = base_width
		self.envelope_power = envelope_power
		self.num_whorls = num_whorls
		self.num_internodals = num_internodals
		self.trunk_bend = trunk_bend
		self.droop_factor = droop_factor
		self.asymmetry = asymmetry
		self.bark_color = bark_color


def generate_tree(config):
	"""Generate a single conifer sprite image deterministically."""
	rng = Random(config.seed)  # nosec B311 - deterministic artwork generation, not security-sensitive randomness.

	img = Image.new('RGBA', (CANVAS_WIDTH, CANVAS_HEIGHT), (0, 0, 0, 0))
	draw = ImageDraw.Draw(img)

	# Direct sunlight direction from upper sky, slightly front and left.
	sun_dir = (-0.24, -0.92, 0.28)
	sun_len = math.hypot(sun_dir[0], sun_dir[1], sun_dir[2])
	lx, ly, lz = sun_dir[0] / sun_len, sun_dir[1] / sun_len, sun_dir[2] / sun_len

	base_y = float(CANVAS_HEIGHT)
	tree_height = base_y - config.apex_y
	trunk_center_x = CANVAS_WIDTH * 0.5

	def get_trunk_x(y):
		t = (y - config.apex_y) / tree_height
		bend = config.trunk_bend
		return trunk_center_x + bend * 4.5 * math.sin(t * 2.5 + 0.3) + bend * 1.5 * math.sin(t * 6.2)

	def get_trunk_radius(y):
		t = (y - config.apex_y) / tree_height
		return 1.8 + (config.base_width * 0.082) * (t ** 0.88)

	draw_queue = []

	# 1. Trunk segments.
	y_steps = np.linspace(config.apex_y, base_y, 160)
	for i in range(len(y_steps) - 1):
		y0, y1 = y_steps[i], y_steps[i + 1]
		x0, x1 = get_trunk_x(y0), get_trunk_x(y1)
		r0, r1 = get_trunk_radius(y0), get_trunk_radius(y1)

		def make_trunk_segment(x0=x0, y0=y0, x1=x1, y1=y1, r0=r0, r1=r1):
			def _draw(d):
				base_c = config.bark_color
				d.polygon([(x0 - r0, y0), (x0 + r0, y0), (x1 + r1, y1), (x1 - r1, y1)], fill=(*base_c, 255))
				for strip in np.linspace(-0.85, 0.85, 6):
					shade = 1.0 - abs(strip) * 0.45
					c = (int(base_c[0] * 1.5 * shade), int(base_c[1] * 1.5 * shade), int(base_c[2] * 1.5 * shade), 255)
					d.line([(x0 + strip * r0, y0), (x1 + strip * r1, y1)], fill=c, width=max(1, int(r0 * 0.22)))

			return _draw

		draw_queue.append((0.0, make_trunk_segment()))

	# 2. Apex leader shoot with bristling conifer needles.
	def make_apex_leader(leader_seed=rng.randint(0, 1000000)):
		def _draw(d):
			a_rng = Random(leader_seed)  # nosec B311 - deterministic artwork generation.
			leader_pts = [(get_trunk_x(y), y) for y in np.linspace(config.apex_y - 20.0, config.apex_y + 35.0, 25)]
			d.line(leader_pts, fill=(*config.bark_color, 255), width=2)
			for px, py in leader_pts:
				for side in [-1.0, 1.0]:
					for _ in range(2):
						nl = a_rng.uniform(4.0, 7.5)
						ang = a_rng.uniform(0.35, 1.15)
						ex = px + side * math.sin(ang) * nl
						ey = py - math.cos(ang) * nl
						d.line([(px, py), (ex, ey)], fill=(125, 160, 52, 255), width=1)

		return _draw

	draw_queue.append((0.0, make_apex_leader()))

	# 3. Branch distribution: major whorls and internodal filler branches.
	branches = []
	for w_idx in range(config.num_whorls):
		t_whorl = ((w_idx + 1) / (config.num_whorls + 1)) ** 1.04
		n_branches = 4 if t_whorl < 0.18 else (5 if t_whorl < 0.55 else 6)
		base_rot = w_idx * GOLDEN_ANGLE + rng.uniform(-0.20, 0.20)

		for b_idx in range(n_branches):
			y_jitter = rng.uniform(-4.0, 4.0)
			y_origin = config.apex_y + t_whorl * tree_height + y_jitter
			t = clamp((y_origin - config.apex_y) / tree_height, 0.02, 0.98)
			angle = base_rot + b_idx * (2 * math.pi / n_branches) + rng.uniform(-0.20, 0.20)
			branches.append((y_origin, t, angle, True))

	for _ in range(config.num_internodals):
		t = rng.uniform(0.08, 0.96)
		y_origin = config.apex_y + t * tree_height
		angle = rng.uniform(0, 2 * math.pi)
		branches.append((y_origin, t, angle, False))

	for y_origin, t, angle, is_major in branches:
		tx = get_trunk_x(y_origin)

		cone_width = config.base_width * (t ** config.envelope_power)
		if is_major:
			tier_jitter = rng.uniform(0.88, 1.15)
			lat_boost = 1.0 + 0.15 * (abs(math.cos(angle)) ** 1.2)
			reach = cone_width * tier_jitter * lat_boost
		else:
			reach = cone_width * rng.uniform(0.45, 0.72)

		# Asymmetry factor between left and right sides.
		if math.cos(angle) < 0:
			reach *= 1.0 + config.asymmetry
		else:
			reach *= 1.0 - config.asymmetry

		if reach < 6.0:
			continue

		cos_a, sin_a = math.cos(angle), math.sin(angle)
		elev = (0.42 - 0.78 * t) * 0.65 + rng.uniform(-0.06, 0.06)

		# Build 3D branch curve points.
		num_steps = max(5, int(reach / 10.0))
		pts = []
		z_sum = 0.0

		for s in range(num_steps + 1):
			frac = s / num_steps
			dist = frac * reach

			droop = (config.droop_factor * (t ** 1.25) + 3.0) * (frac ** 1.9)
			curl = 9.0 * (max(0.0, frac - 0.65) / 0.35) ** 1.8 if frac > 0.65 else 0.0
			wander_x = 4.0 * math.sin(frac * math.pi * 1.5 + angle)

			bx = tx + dist * cos_a + wander_x
			bz = dist * sin_a
			by = y_origin - dist * math.sin(elev) + droop - curl

			pts.append((bx, by, bz))
			z_sum += bz

		avg_z = z_sum / (num_steps + 1)

		def make_branch_draw(pts=pts, reach=reach, t_tier=t, avg_z=avg_z, b_seed=rng.randint(0, 1000000)):
			def _draw(d):
				b_rng = Random(b_seed)  # nosec B311 - deterministic artwork generation.

				# 1. Main woody branch line.
				stem_2d = [(p[0], p[1]) for p in pts]
				stem_w = max(1, int(3.0 * (1.0 - 0.6 * t_tier)))
				d.line(stem_2d, fill=(*config.bark_color, 255), width=stem_w)

				# 2. Clusters of needle sprays along branch and lateral twigs.
				clusters = []
				for i in range(1, len(pts)):
					frac = i / len(pts)
					if frac < 0.08:
						continue

					p_curr = pts[i]
					p_prev = pts[i - 1]
					p_next = pts[min(i + 1, len(pts) - 1)]

					tdx = p_next[0] - p_prev[0]
					tdy = p_next[1] - p_prev[1]
					tl = math.hypot(tdx, tdy) + 1e-5
					tdx, tdy = tdx / tl, tdy / tl
					tnx, tny = -tdy, tdx

					max_tw_len = reach * 0.38 * (math.sin(frac * math.pi) ** 0.68)

					for side in [-1.0, 1.0]:
						if b_rng.random() < 0.10:
							continue
						tw_l = max_tw_len * b_rng.uniform(0.7, 1.2)
						if tw_l < 4.0:
							continue

						tw_ang = b_rng.uniform(0.55, 0.80)
						tw_dx = tdx * math.cos(tw_ang) + tnx * side * math.sin(tw_ang)
						tw_dy = tdy * math.cos(tw_ang) + tny * side * math.sin(tw_ang) + 0.35 * (t_tier ** 0.5)
						tw_len_norm = math.hypot(tw_dx, tw_dy) + 1e-5
						tw_dx /= tw_len_norm
						tw_dy /= tw_len_norm

						tw_end_x = p_curr[0] + tw_dx * tw_l
						tw_end_y = p_curr[1] + tw_dy * tw_l + 4.0 * (t_tier ** 1.3)

						d.line([(p_curr[0], p_curr[1]), (tw_end_x, tw_end_y)], fill=(*config.bark_color, 255), width=1)

						for sub_f in [0.35, 0.70, 1.0]:
							cx = p_curr[0] + (tw_end_x - p_curr[0]) * sub_f
							cy = p_curr[1] + (tw_end_y - p_curr[1]) * sub_f
							c_rad = max(6.5, tw_l * 0.46 * (0.85 if sub_f < 1.0 else 1.0))
							clusters.append(((cx, cy), (tw_dx, tw_dy), c_rad))

					if b_rng.random() < 0.75:
						clusters.append(((p_curr[0], p_curr[1]), (tdx, tdy), reach * 0.14))

				# 3. Foliage rendering with depth lighting and layered needles.
				depth_factor = clamp(0.35 + 0.65 * ((avg_z + 100.0) / 200.0), 0.22, 1.0)
				tier_factor = clamp(0.35 + 0.65 * (1.0 - t_tier * 0.50), 0.28, 1.0)

				# Pass A: Underlay shadow needles.
				for c_pos, (cdx, cdy), c_rad in clusters:
					sh_rgb = (int(13 * depth_factor * tier_factor + 3), int(25 * depth_factor * tier_factor + 5), int(11 * depth_factor * tier_factor + 3))

					n_shadow = int(c_rad * 1.8)
					base_ang = math.atan2(cdy, cdx)
					for _ in range(n_shadow):
						a_off = b_rng.uniform(-1.3, 1.3)
						ang = base_ang + a_off
						ndx, ndy = math.cos(ang), math.sin(ang)
						nl = b_rng.uniform(c_rad * 0.4, c_rad * 0.85)

						bx = c_pos[0] + b_rng.uniform(-2.5, 2.5)
						by = c_pos[1] + b_rng.uniform(-2.5, 2.5)
						ex = bx + ndx * nl
						ey = by + ndy * nl
						d.line([(bx, by), (ex, ey)], fill=(*sh_rgb, 225), width=max(1, int(b_rng.choice([1, 2]))))

				# Pass B: Detailed needles with lighting & base-to-tip gradient.
				for c_pos, (cdx, cdy), c_rad in clusters:
					base_ang = math.atan2(cdy, cdx)
					n_needles = int(c_rad * 2.2)

					for _ in range(n_needles):
						a_off = b_rng.uniform(-1.25, 1.25)
						ang = base_ang + a_off
						ndx, ndy = math.cos(ang), math.sin(ang)

						nl = b_rng.uniform(5.5, 10.0)
						bx = c_pos[0] + b_rng.uniform(-3.0, 3.0)
						by = c_pos[1] + b_rng.uniform(-2.5, 2.5)

						mid_x = bx + ndx * (nl * 0.48)
						mid_y = by + ndy * (nl * 0.48)
						tip_x = bx + ndx * nl
						tip_y = by + ndy * nl

						norm_x = ndx
						norm_y = ndy - 0.45
						norm_z = 0.4 if avg_z > 0 else -0.4
						n_norm = math.hypot(norm_x, norm_y, norm_z) + 1e-5
						norm_x /= n_norm
						norm_y /= n_norm
						norm_z /= n_norm

						dot_l = max(0.0, norm_x * lx + norm_y * ly + norm_z * lz)

						amb_tier = 0.35 + 0.65 * (1.0 - t_tier * 0.42)
						amb_depth = clamp(0.4 + 0.6 * ((avg_z + 80.0) / 160.0), 0.22, 1.0)
						top_bias = 1.0 if ndy < 0 else 0.52

						light_tip = (0.26 + 0.74 * dot_l) * amb_tier * amb_depth * top_bias
						light_base = light_tip * 0.42

						c_base = light_to_color(light_base)
						c_tip = light_to_color(light_tip)
						d.line([(bx, by), (mid_x, mid_y)], fill=(*c_base, 255), width=1)
						d.line([(mid_x, mid_y), (tip_x, tip_y)], fill=(*c_tip, 255), width=1)

			return _draw

		draw_queue.append((avg_z, make_branch_draw()))

	# Sort all draw items back-to-front by 3D depth Z.
	draw_queue.sort(key=lambda item: item[0])

	for _, func in draw_queue:
		func(draw)

	return img


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
