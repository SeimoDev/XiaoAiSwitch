import unittest
import xml.etree.ElementTree as ET

from update_star_history import record, render


class StarHistoryTest(unittest.TestCase):
    def test_same_day_updates_and_unchanged_count(self):
        points = record([], "2026-11-17", 0)
        self.assertEqual(record(points, "2026-11-18", 0), points)
        points = record(points, "2026-11-18", 3)
        self.assertEqual(record(points, "2026-11-18", 2), [["2026-11-17", 0], ["2026-11-18", 2]])

    def test_empty_repository_has_real_zero_not_missing_data(self):
        svg = ET.fromstring(render([["2026-11-17", 0]]))
        self.assertEqual(svg.attrib["role"], "img")
        self.assertIn("0 Stars", " ".join(svg.itertext()))

    def test_draws_step_path_between_observed_days(self):
        svg = ET.fromstring(render([["2026-11-17", 0], ["2026-11-18", 7], ["2026-11-19", 4]]))
        line = svg.find("{http://www.w3.org/2000/svg}path")
        self.assertEqual(line.attrib["d"].count(" V "), 2)
        self.assertIn("4 Stars", " ".join(svg.itertext()))


if __name__ == "__main__":
    unittest.main()
