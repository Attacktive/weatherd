import unittest

from provider_evaluation.instrumentation_safety import select_ordinary_device


class InstrumentationSafetyTest(unittest.TestCase):
	def test_implicit_selection_refuses_multiple_connected_devices(self):
		devices = {'emulator-5554': 'Pixel', 'emulator-5556': 'Pixel_Weatherd_Provider_Evaluation'}
		with self.assertRaises(ValueError):
			select_ordinary_device(devices, [])

	def test_explicit_safe_device_does_not_include_the_collector(self):
		devices = {'emulator-5554': 'Pixel', 'emulator-5556': 'Pixel_Weatherd_Provider_Evaluation'}
		self.assertEqual(select_ordinary_device(devices, ['emulator-5554']), 'emulator-5554')

	def test_reserved_collector_is_refused_even_when_it_is_the_only_device(self):
		devices = {'emulator-5556': 'Pixel_Weatherd_Provider_Evaluation'}
		for requested in ([], ['emulator-5556']):
			with self.subTest(requested=requested), self.assertRaises(ValueError):
				select_ordinary_device(devices, requested)

	def test_even_one_device_requires_explicit_selection(self):
		with self.assertRaises(ValueError):
			select_ordinary_device({'emulator-5582': 'WeatherdIsolation'}, [])

	def test_missing_or_multiple_requested_devices_are_refused(self):
		devices = {'emulator-5554': 'Pixel'}
		for requested in (['emulator-5558'], ['emulator-5554', 'emulator-5558']):
			with self.subTest(requested=requested), self.assertRaises(ValueError):
				select_ordinary_device(devices, requested)

	def test_an_unidentified_emulator_is_not_assumed_safe(self):
		with self.assertRaises(ValueError):
			select_ordinary_device({'emulator-5556': ''}, ['emulator-5556'])

	def test_no_connected_device_is_an_error(self):
		with self.assertRaises(ValueError):
			select_ordinary_device({}, [])
