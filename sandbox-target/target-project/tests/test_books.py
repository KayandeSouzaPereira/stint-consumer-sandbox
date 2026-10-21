import unittest

from catalog.books import Book, Catalog


class CatalogTest(unittest.TestCase):
    def setUp(self):
        self.catalog = Catalog()
        self.catalog.add(Book("9780000000002", "Beta", "Author One", 2001))
        self.catalog.add(Book("9780000000019", "Alpha", "Author Two", 1999))

    def test_add_and_get(self):
        self.assertEqual("Beta", self.catalog.get("9780000000002").title)

    def test_duplicate_isbn_is_refused(self):
        with self.assertRaises(ValueError):
            self.catalog.add(Book("9780000000002", "Gamma", "Author", 2000))

    def test_all_is_sorted_by_title(self):
        self.assertEqual(["Alpha", "Beta"], [b.title for b in self.catalog.all()])

    def test_remove(self):
        self.assertTrue(self.catalog.remove("9780000000019"))
        self.assertEqual(1, self.catalog.count())


if __name__ == "__main__":
    unittest.main()
