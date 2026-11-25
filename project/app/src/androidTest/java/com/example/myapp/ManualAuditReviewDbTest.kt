package com.example.myapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ManualAuditReviewDbTest {
    @Test fun updatesSurviveReopeningAndRemainScopedToThePhotoAndReviewer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "manual_audit_test_${UUID.randomUUID()}.db"
        val key = ManualAuditScope("ABC-002", "reviewer-1", "WH1", "same-photo-digest", false)
        val position = ManualAuditPosition("B/R08/1/-", "B R08-1F")
        val original = ManualAuditReview(position, ManualAuditValues("00123", 4))
        val updated = original.copy(values = ManualAuditValues("001234", 0))
        try {
            ManualAuditReviewDb(context, databaseName).use { db ->
                db.save(key, original)
                db.save(key, updated)
            }
            ManualAuditReviewDb(context, databaseName).use { reopened ->
                assertEquals(mapOf(position.id to updated), reopened.read(key))
                assertTrue(reopened.read(key.copy(reviewerUid = "reviewer-2")).isEmpty())
                assertTrue(reopened.read(key.copy(companyCode = "ABC-001")).isEmpty())
                assertTrue(reopened.read(key.copy(warehouseCode = "WH2")).isEmpty())
                assertTrue(reopened.read(key.copy(photoDigest = "other-photo")).isEmpty())
                assertTrue(reopened.read(key.copy(testRackLabels = true)).isEmpty())
            }
        } finally {
            context.deleteDatabase(File(context.noBackupFilesDir, databaseName).path)
        }
    }
}
