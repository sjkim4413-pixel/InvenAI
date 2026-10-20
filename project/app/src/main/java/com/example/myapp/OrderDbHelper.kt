package com.example.myapp

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

// REQ_01_02_02: 로컬 DB (SQLite) 캐싱 클래스
class OrderDbHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "expected_orders.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_NAME = "orders"

        private const val COLUMN_ORDER_ID = "order_id"
        private const val COLUMN_ITEM_CODE = "item_code"
        private const val COLUMN_ITEM_NAME = "item_name"
        private const val COLUMN_QUANTITY = "quantity"
        private const val COLUMN_LOCATION = "location"
        private const val COLUMN_STATUS = "status"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTableQuery = ("CREATE TABLE " + TABLE_NAME + " ("
                + COLUMN_ORDER_ID + " TEXT PRIMARY KEY,"
                + COLUMN_ITEM_CODE + " TEXT,"
                + COLUMN_ITEM_NAME + " TEXT,"
                + COLUMN_QUANTITY + " INTEGER,"
                + COLUMN_LOCATION + " TEXT,"
                + COLUMN_STATUS + " TEXT" + ")")
        db.execSQL(createTableQuery)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
        onCreate(db)
    }

    // 수신된 적재 오더 데이터를 로컬 DB에 캐싱
    fun cacheOrders(orders: List<OrderResponse>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM $TABLE_NAME")
            for (order in orders) {
                val values = ContentValues().apply {
                    put(COLUMN_ORDER_ID, order.orderId)
                    put(COLUMN_ITEM_CODE, order.itemCode)
                    put(COLUMN_ITEM_NAME, order.itemName)
                    put(COLUMN_QUANTITY, order.quantity)
                    put(COLUMN_LOCATION, order.location)
                    put(COLUMN_STATUS, order.status)
                }
                db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // 통신 단절(오프라인) 시 로컬 DB 캐시 데이터 불러오기
    fun getCachedOrders(): List<OrderResponse> {
        val orderList = mutableListOf<OrderResponse>()
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT * FROM $TABLE_NAME", null)
        if (cursor.moveToFirst()) {
            do {
                val orderId = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_ORDER_ID))
                val itemCode = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_ITEM_CODE))
                val itemName = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_ITEM_NAME))
                val quantity = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_QUANTITY))
                val location = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_LOCATION))
                val status = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_STATUS))
                orderList.add(OrderResponse(orderId, itemCode, itemName, quantity, location, status))
            } while (cursor.moveToNext())
        }
        cursor.close()
        return orderList
    }
}