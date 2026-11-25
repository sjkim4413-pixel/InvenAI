package com.example.myapp

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

internal class ManualAuditReviewDb(context: Context, name: String = "manual_audit_reviews.db") :
    SQLiteOpenHelper(context, File(context.noBackupFilesDir, name).path, null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE manual_audit_reviews (
                company_code TEXT NOT NULL,
                reviewer_uid TEXT NOT NULL,
                warehouse_code TEXT NOT NULL,
                photo_digest TEXT NOT NULL,
                test_rack_labels INTEGER NOT NULL CHECK(test_rack_labels IN (0, 1)),
                position_id TEXT NOT NULL,
                position_label TEXT NOT NULL,
                barcode TEXT NOT NULL CHECK(length(barcode) BETWEEN 1 AND 256),
                quantity INTEGER NOT NULL CHECK(quantity BETWEEN 0 AND 2147483647),
                PRIMARY KEY (company_code, reviewer_uid, warehouse_code, photo_digest,
                             test_rack_labels, position_id)
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Manual audit review database migration $oldVersion to $newVersion is required.")
    }

    fun read(scope: ManualAuditScope): Map<String, ManualAuditReview> {
        return readableDatabase.rawQuery(
            """
            SELECT position_id, position_label, barcode, quantity FROM manual_audit_reviews
            WHERE company_code = ? AND reviewer_uid = ? AND warehouse_code = ?
              AND photo_digest = ? AND test_rack_labels = ?
            ORDER BY position_label, position_id
            """.trimIndent(),
            arrayOf(scope.companyCode, scope.reviewerUid, scope.warehouseCode, scope.photoDigest,
                if (scope.testRackLabels) "1" else "0")
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val position = ManualAuditPosition(cursor.getString(0), cursor.getString(1))
                    put(position.id, ManualAuditReview(position,
                        ManualAuditValues(cursor.getString(2), cursor.getInt(3))))
                }
            }
        }
    }

    fun save(scope: ManualAuditScope, review: ManualAuditReview) {
        val values = ContentValues().apply {
            put("company_code", scope.companyCode)
            put("reviewer_uid", scope.reviewerUid)
            put("warehouse_code", scope.warehouseCode)
            put("photo_digest", scope.photoDigest)
            put("test_rack_labels", if (scope.testRackLabels) 1 else 0)
            put("position_id", review.position.id)
            put("position_label", review.position.label)
            put("barcode", review.values.barcode)
            put("quantity", review.values.quantity)
        }
        check(writableDatabase.insertWithOnConflict(
            "manual_audit_reviews", null, values, SQLiteDatabase.CONFLICT_REPLACE
        ) != -1L) { "Manual audit review could not be saved." }
    }
}
