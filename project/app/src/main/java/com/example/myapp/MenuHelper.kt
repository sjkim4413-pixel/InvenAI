package com.example.myapp

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.firebase.auth.FirebaseAuth

object MenuHelper {
    fun showProfile(activity: Activity) {
        val session = activity.getSharedPreferences("app_user_session", Activity.MODE_PRIVATE)
        val company = InventoryManager.activeCompanyCode(activity)
        val role = InventoryManager.role(activity)
        val employee = session.getString("USER_ID", "").orEmpty()
        val roleName = when (role) { "QA" -> "검수자"; "LOADER" -> "적재자"; else -> "확인 필요" }
        val details = if (company == null) "다시 로그인해 주세요."
            else "회사 · $company\n아이디 · $employee\n권한 · $roleName"
        AlertDialog.Builder(activity).setTitle("계정 정보").setMessage(details)
            .setPositiveButton("닫기", null).show()
    }

    fun showMoreMenu(activity: Activity) {
        val sheet = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.bottom_sheet_more_menu, null)
        for (id in listOf(R.id.item_profile, R.id.item_erp, R.id.item_outbound,
                         R.id.item_settings, R.id.item_notice, R.id.item_logout)) {
            view.findViewById<View>(id)?.let { item ->
                WarehouseMenus.decorateAnchor(item)
                (item.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.bottomMargin =
                    (8 * activity.resources.displayMetrics.density).toInt()
            }
        }
        fun route(id: Int, screen: Class<*>) {
            view.findViewById<View>(id).setOnClickListener {
                sheet.dismiss()
                activity.startActivity(Intent(activity, screen))
            }
        }

        view.findViewById<View>(R.id.item_profile).setOnClickListener {
            sheet.dismiss()
            showProfile(activity)
        }
        route(R.id.item_erp, ErpWmsConnection::class.java)
        val canInspect = InventoryManager.role(activity) == "QA"
        view.findViewById<View>(R.id.item_outbound).visibility = if (canInspect) View.VISIBLE else View.GONE
        route(R.id.item_outbound, Outbound::class.java)
        view.findViewById<View>(R.id.item_settings).setOnClickListener {
            sheet.dismiss()
            activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", activity.packageName, null)))
        }
        view.findViewById<View>(R.id.item_notice).setOnClickListener {
            sheet.dismiss()
            AlertDialog.Builder(activity).setTitle("사용 도움말")
                .setMessage("카메라 사용이 차단되면 앱 권한 설정을 확인해 주세요.\n회사 코드와 작업 권한은 소속 회사 관리자에게 문의해 주세요.")
                .setPositiveButton("닫기", null).show()
        }
        view.findViewById<View>(R.id.item_logout).setOnClickListener {
            sheet.dismiss()
            F06PushRegistration.unregister(activity)
            FirebaseAuth.getInstance().signOut()
            activity.getSharedPreferences("app_user_session", Activity.MODE_PRIVATE).edit().clear().apply()
            activity.startActivity(Intent(activity, Frame1::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            activity.finish()
        }
        sheet.setContentView(view)
        sheet.show()
    }
}
