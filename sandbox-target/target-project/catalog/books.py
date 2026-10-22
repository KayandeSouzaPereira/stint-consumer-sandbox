"""A small in-memory book catalog (SDD S.5, 8f). Synthetic data only; standard library only."""
from dataclasses import dataclass


@dataclass(frozen=True)
class Book:
    isbn: str
    title: str
    author: str
    year: int


class Catalog:
    def __init__(self):
        self._books = {}

    def add(self, book):
        if not book.title or not book.author:
            raise ValueError("a book needs a title and an author")
        if book.isbn in self._books:
            raise ValueError("duplicate isbn")
        self._books[book.isbn] = book

    def get(self, isbn):
        return self._books.get(isbn)

    def remove(self, isbn):
        return self._books.pop(isbn, None) is not None

    def all(self):
        return sorted(self._books.values(), key=lambda b: (b.title, b.isbn))

    def count(self):
        return len(self._books)
