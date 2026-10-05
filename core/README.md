# Shelf domain

This JVM module owns the current document contract and has no Android dependencies.

- Only schema 4 is accepted. Serialized documents require an explicit version and shelf dimensions. No compatibility readers or migrations exist.
- A shelf has one row, indexed 0. Dimensions are millimeters; rendering uses 100 mm per world unit. The floor is at Y=0.25 and X=0 is the shelf center.
- Every book is a registered `BookInfo` in the document; there is no built-in sample catalog. Books keep their physical width, height and thickness. Thickness uses a measured value, an automatic bibliographic value, or the page estimate. Flat stacks accumulate thickness and use their largest width/depth.
- Items cannot overlap or exceed the shelf volume. A book appears at most once; multiple books require FLAT orientation. Up to six props are allowed.
- `ShelfEditor` commits whole valid documents and keeps up to 100 undo snapshots. New edits clear redo. Failed edits do not change geometry or history.
- `preview` does not mutate. Snapping uses entry radius 0.14 and exit radius 0.24, with shelf edges, neighbors and equal free space as candidates.
- `ShelfCodec` validates reads and writes. The Android store owns atomic I/O and recovery from write failure.
- `ShelfEditor()` starts from an empty 360 × 270 × 270 mm shelf. `ShelfGeometry.bookError` validates a single book, also for the device reading list.

Run `./gradlew :core:test` from the repository root.
