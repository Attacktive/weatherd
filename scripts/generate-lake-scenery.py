#!/usr/bin/env python3
"""Generate the original lake banks and their shoreline-aligned reflection."""

from math import cos, exp, floor, sin
from pathlib import Path
from random import Random

from PIL import Image, ImageDraw, ImageFilter

WIDTH, HEIGHT = 500, 900
OUTPUT = Path(__file__).resolve().parents[1] / 'app/src/main/res/drawable-nodpi'
random = Random(470)  # nosec B311 - deterministic artwork generation, not security-sensitive randomness.
field = [random.random() for _ in range(256 * 256)]  # nosec B311


def smooth(value):
	value = min(1.0, max(0.0, value))
	return value * value * (3 - 2 * value)


def noise(x, y):
	i, j = floor(x), floor(y)
	u, v = smooth(x - i), smooth(y - j)
	p, q = i & 255, (i + 1) & 255
	r, s = (j & 255) * 256, ((j + 1) & 255) * 256
	a = field[r + p] + (field[r + q] - field[r + p]) * u
	b = field[s + p] + (field[s + q] - field[s + p]) * u
	return a + (b - a) * v


def mix(a, b, amount):
	amount = min(1.0, max(0.0, amount))
	return tuple(round(x + (y - x) * amount) for x, y in zip(a, b))


def bank_column(kind, x):
	u = x / WIDTH
	if kind == 0:
		end = 604 + 2 * noise(x / 90, 1)
		height = 53 + 11 * cos(u * 6.8 - .55) + 7 * sin(u * 13.6 + 1.2) + 4 * cos(u * 20.1) + 3 * noise(x / 35, 4) + 1.4 * noise(x / 12, 31)
		return end, height, (113, 151, 170), (154, 179, 186)

	if kind == 1:
		t = smooth((u - .22) / .78)
		end = 602 + 15 * t
		height = t ** .6 * (31 + 15 * noise(x / 53, 8) + 6 * noise(x / 8, 15))
		return end, height, (75, 113, 122), (118, 150, 143)

	t = smooth((.63 - u) / .63)
	end = 606 + 27 * t
	height = t ** .6 * (46 + 14 * noise(x / 23, 6))
	return end, height, (42, 82, 82), (92, 128, 103)


def tree_crowns(kind, x, top, height, shade, light):
	rng = Random(2000 * kind + x)  # nosec B311 - deterministic artwork generation, not security-sensitive randomness.
	cluster = smooth((noise(x / (24 if kind == 2 else 52), 31 + kind) - .28) / .48)
	if rng.random() > cluster * (.64 if kind == 2 else .27):  # nosec B311
		return

	size = min(height * .73, (3 + cluster * (6 + 12 * rng.random())) * (1 if kind == 2 else .37))  # nosec B311
	for k in range(3):
		y = top - size + k * size * .22
		w = size * (.12 + .035 * k)
		yield ([(x, y), (x - w, y + size * .57), (x + w * .9, y + size * .56)], mix(shade, light, .15 + .45 * rng.random()))  # nosec B311


def bank(kind):
	image = Image.new('RGBA', (WIDTH, HEIGHT))
	draw = ImageDraw.Draw(image)
	crowns = []
	ends = []
	for x in range(WIDTH):
		end, height, shade, light = bank_column(kind, x)

		ends.append(round(end))
		if height < .6:
			continue

		top = int(end - height)
		for y in range(top, round(end) + 1):
			value = .15 + .46 * noise(x / 22, y / 14) + .28 * noise(x / 7, y / 9) + .19 * noise(x / 2.4, y / 3)
			color = mix(shade, light, value - .34 * exp(-abs(y - end) / 3))
			image.putpixel((x, y), (*color, 255))

		if kind:
			crowns.extend(tree_crowns(kind, x, top, height, shade, light))

	for points, color in crowns:
		draw.polygon(points, fill=(*color, 255))

	return image, ends


def main():
	land = Image.new('RGBA', (WIDTH, HEIGHT))
	reflection = Image.new('RGBA', (WIDTH, HEIGHT))
	for kind in range(3):
		image, ends = bank(kind)
		land.alpha_composite(image)
		blurred = image.convert('RGBa').filter(ImageFilter.GaussianBlur(1.15)).convert('RGBA')
		layer = Image.new('RGBA', (WIDTH, HEIGHT))
		source, target = blurred.load(), layer.load()
		for x in range(WIDTH):
			end = ends[x]
			for y in range(end, min(HEIGHT, end + 140)):
				sy = round(end - (y - end) * 1.15)
				if sy >= 0:
					red, green, blue, alpha = source[x, sy]
					target[x, y] = (red, green, blue, round(alpha * .87 * max(0, 1 - (y - end) / 200)))

		reflection.alpha_composite(layer)

	land.save(OUTPUT / 'lake_banks.png', optimize=True)
	reflection.save(OUTPUT / 'lake_banks_reflected.png', optimize=True)


if __name__ == '__main__':
	main()
