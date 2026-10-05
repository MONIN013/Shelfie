package net.monindev.shelfie.app

import net.monindev.shelfie.filament.FilamentShelfRenderer
import net.monindev.shelfie.render.ShelfRenderer
import net.monindev.shelfie.render.ShelfSnapshotter

fun createShelfRenderer(): ShelfRenderer = FilamentShelfRenderer()
fun createShelfSnapshotter(): ShelfSnapshotter = FilamentShelfRenderer()
