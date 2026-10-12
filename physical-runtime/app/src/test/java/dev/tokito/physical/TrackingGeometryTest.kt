package dev.tokito.physical

import ai.tokito.physical.ar.Point2
import org.junit.Assert.*
import org.junit.Test

class TrackingGeometryTest {
    private val quad = listOf(Point2(100.0,100.0), Point2(500.0,100.0), Point2(500.0,400.0), Point2(100.0,400.0))
    @Test fun acceptsCompleteBoard() { assertTrue(TrackingGeometry.accept(quad,640,480)) }
    @Test fun rejectsReflectionAndCrossedGeometry() {
        assertFalse(TrackingGeometry.accept(quad.reversed(),640,480))
        assertFalse(TrackingGeometry.accept(listOf(quad[0],quad[2],quad[1],quad[3]),640,480))
    }
    @Test fun rejectsNonFiniteAndClippedBoards() {
        assertFalse(TrackingGeometry.accept(quad.map { Point2(it.x+400,it.y) },640,480))
        assertThrows(IllegalArgumentException::class.java) { Point2(Double.NaN,0.0) }
    }
    @Test fun rejectsTinyBoardAndDegeneratePlane() {
        assertFalse(TrackingGeometry.accept(quad.map { Point2(it.x*0.01,it.y*0.01) },640,480))
        assertFalse(TrackingGeometry.accept(List(4) { Point2(50.0,50.0) },640,480))
    }
}
