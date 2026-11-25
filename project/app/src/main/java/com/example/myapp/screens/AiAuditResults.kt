package com.example.myapp

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.ViewModelProvider
import com.bumptech.glide.Glide

class AiAuditResults : AppCompatActivity() {

	private lateinit var viewModel: AuditResultViewModel
	private lateinit var photoView: AuditPhotoView
	private var ocrTokens = emptyList<F03OcrToken>()
	private var comparisonRows = emptyList<F03ComparedPosition>()
	private var positionList: LinearLayout? = null
	private var alertDeliveryRow: LinearLayout? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_ai_audit_results)


		// 상단 및 하단 배경 Glide 로드
		findViewById<View>(R.id.r92jaxcy3c4)?.visibility = View.GONE

		viewModel = ViewModelProvider(this)[AuditResultViewModel::class.java]
		viewModel.open(
			CapturedPhotoHolder.getAndClear(),
			savedInstanceState?.getString("uploadedFilename"),
			savedInstanceState?.getString("warehouseCode") ?: intent.getStringExtra("WAREHOUSE_CODE"),
			intent.getBooleanExtra(EXTRA_TEST_RACK_LABELS, false),
			savedInstanceState?.getString("photoDigest"),
			savedInstanceState?.getString("photoOwnerUid"),
			intent.getStringExtra(EXTRA_AUDIT_TEST_MODE),
			intent.getStringExtra(EXTRA_AUDIT_TEST_WAREHOUSE)
		)
		photoView = findViewById(R.id.ivAiCapturedPhoto)
		val photo = viewModel.photoBytes
		if (photo != null) {
			Glide.with(this).asBitmap().load(photo).fitCenter().into(photoView)
		} else {
			findViewById<View>(R.id.auditPhotoPanel).visibility = View.GONE
		}
		photoView.onMarkClick = { mark -> showPositionDetails(mark.position, mark.detection) }

		// 상단 뒤로가기
		findViewById<View>(R.id.r3qa1ncjec6t)?.setOnClickListener { finish() }

		// 1. [재촬영] 버튼
		val btnRetake: View? = findViewById(R.id.rn409sprmgg)
		btnRetake?.setOnClickListener {
			val intent = Intent(this, AiAuditCamera::class.java).apply {
				putExtra("WAREHOUSE_CODE", viewModel.warehouseCode ?: intent.getStringExtra("WAREHOUSE_CODE"))
			}
			startActivity(intent)
			finish()
		}

		findViewById<View>(R.id.r0e5j960n87jp)?.visibility = View.GONE

		// 하단 네비게이션 바 연결
		findViewById<View>(R.id.rte7pemp4q98)?.setOnClickListener {
			openWarehouseHome(this)
		}

		val openInventory = View.OnClickListener {
			startActivity(Intent(this, InventoryList::class.java))
			finish()
		}
		findViewById<View>(R.id.rklq2ru6dpxi)?.setOnClickListener(openInventory)
		findViewById<View>(R.id.r9hjk1laagf4)?.setOnClickListener(openInventory)

		val openScanner = View.OnClickListener {
			startActivity(Intent(this, BarcodeScanner::class.java))
		}
		findViewById<View>(R.id.r0ygmbl1hl7wq)?.setOnClickListener(openScanner)
		findViewById<View>(R.id.rttg048oiico)?.setOnClickListener(openScanner)

		val openWork = View.OnClickListener {
			startActivity(Intent(this, WorkHistory::class.java))
		}
		findViewById<View>(R.id.rv9avan314c)?.setOnClickListener(openWork)
		findViewById<View>(R.id.re4xglprgpaq)?.setOnClickListener(openWork)

		val openMore = View.OnClickListener {
			MenuHelper.showMoreMenu(this)
		}
		findViewById<View>(R.id.rza5cxscdic)?.setOnClickListener(openMore)
		findViewById<View>(R.id.rh7vg1bgh24r)?.setOnClickListener(openMore)

		viewModel.state.observe(this) { state ->
			when (state) {
				AuditResultState.Loading -> showMessage(
					"사진을 분석하고 ERP와 대조 중입니다.", retry = false, loading = true
				)
				is AuditResultState.Failure -> showMessage(state.message, state.canRetry)
				is AuditResultState.WarehouseRequired -> showWarehouseSelection(state.warehouses)
				is AuditResultState.Ready -> renderComparison(state.result)
			}
		}
		viewModel.manualReviews.observe(this) { renderPositions() }
		viewModel.alertDelivery.observe(this) { renderAlertDelivery() }
	}

	override fun onSaveInstanceState(outState: Bundle) {
		super.onSaveInstanceState(outState)
		outState.putString("uploadedFilename", viewModel.uploadedFilename)
		outState.putString("warehouseCode", viewModel.warehouseCode)
		outState.putString("photoDigest", viewModel.photoDigest)
		outState.putString("photoOwnerUid", viewModel.photoOwnerUid)
	}

	private fun showWarehouseSelection(warehouses: List<String>) {
		showMessage("사진을 찍은 창고를 선택해주세요.", retry = false)
		val container = findViewById<LinearLayout>(R.id.llAuditDetailContainer)
		addAction(container, "실제 창고 선택") {
			WarehouseMenus.show(this, "실제 창고 선택", warehouses) { index -> viewModel.selectWarehouse(warehouses[index]) }
		}
	}

	private fun showMessage(message: String, retry: Boolean, loading: Boolean = false) {
		positionList = null
		alertDeliveryRow = null
		findViewById<View>(R.id.r0e5j960n87jp).visibility = View.GONE
		photoView.showMarks(emptyList())
		findViewById<View>(R.id.auditPhotoLegend).visibility = View.GONE
		setSummary("—", "—", "—")
		val container = findViewById<LinearLayout>(R.id.llAuditDetailContainer)
		container.removeAllViews()
		if (loading) container.addView(ProgressBar(this))
		container.addView(TextView(this).apply { text = message })
		if (retry) addAction(container, "다시 시도") { viewModel.requestComparison() }
	}

	private fun setSummary(total: String, verified: String, review: String) {
		findViewById<TextView>(R.id.rcmltsyaqay).text = total
		findViewById<TextView>(R.id.r1o3adrmw6zi).text = verified
		findViewById<TextView>(R.id.rd1claoa9qi).text = review
	}

	private fun renderComparison(result: F03AnalyzeAndCompareResponse) {
		val rows = result.results.orEmpty()
		ocrTokens = result.ocrTokens.orEmpty()
		val testRackLabels = result.verificationScope == "test_rack_label_as_item_code"
		val testProducts = result.verificationScope == "test_annotated_product_locations"
		val testReferences = result.verificationScope == "test_reference_product_locations"
		findViewById<View>(R.id.r0e5j960n87jp).visibility = View.GONE
		val verified = rows.count { it.isMatch }
		val anomalies = rows.count { it.isConfirmedAnomaly }
		findViewById<TextView>(R.id.rlbdvoyreobi).text = when {
			testReferences -> "기준 사진·위치 일치"
			testProducts -> "테스트 상품·위치 일치"
			testRackLabels -> "테스트 라벨 일치"
			else -> "상품·위치 일치"
		}
		setSummary("${rows.size}건", "${verified}건", "${rows.size - verified}건")

		val marks = result.photoMarks()
		photoView.showMarks(marks)
		findViewById<TextView>(R.id.auditPhotoLegend).apply {
			visibility = if (marks.isEmpty()) View.GONE else View.VISIBLE
			text = if (testReferences) "초록 기준 상품 일치 · 빨강 오적재 · 주황 외관 확인 필요\n테두리를 누르면 기준 상품과 관측 상품을 볼 수 있습니다."
			else if (testRackLabels) "초록 테스트 라벨 일치 · 주황 확인 필요\n테두리를 누르면 OCR 대조 근거를 볼 수 있습니다."
			else "초록 상품·위치 일치 · 빨강 오적재·미등록 · 주황 확인 필요\n테두리를 누르면 관측 상품과 전산 정보를 볼 수 있습니다."
		}

		val container = findViewById<LinearLayout>(R.id.llAuditDetailContainer)
		container.removeAllViews()
		intent.getStringExtra(EXTRA_AUDIT_TEST_TITLE)?.let { title ->
			container.addView(TextView(this).apply {
				text = if (testReferences) "$title\n원본 기준 상품 외관 비교 · 수량 별도 확인"
				else if (testProducts) "$title\nF05 지정 영역 OCR 비교 · AI 편집 사진 · 수량 별도 확인"
				else title
				setTypeface(typeface, Typeface.BOLD)
				setPadding(0, 0, 0, dpToPx(12))
			})
		}
		val source = if (result.erpComparisonAvailable == false) {
			"Firestore 조회 실패 · ERP 대조 보류"
		} else when (result.dataSource) {
			"FIRESTORE_TEST_ERP" -> "Firestore · ${result.erpWarehouse ?: "테스트 창고"}"
			"FIRESTORE_UNAVAILABLE" -> "Firestore 조회 실패 · ERP 대조 보류"
			"FIREBASE_READ_ONLY" -> result.erpWarehouse?.let { "Firestore · $it" } ?: "Firestore · 확인된 창고 없음"
			"LOCAL_TEST_ERP", "SIMULATED_AI_IMAGE_ERP", "SIMULATED_TRADERS_ERP" -> "서버 테스트 CSV"
			else -> result.dataSource ?: "확인되지 않음"
		}
		val ocrState = when (result.ocrStatus) {
			"completed" -> "OCR 완료"
			"partial_limit" -> "OCR 일부 완료"
			null -> if (result.ocrTokens == null) "OCR 상태 미제공" else "OCR 결과"
			else -> "OCR 상태 확인 필요"
		}
		val ocrIdentified = rows.count { (it.ocrMaxConfidence ?: 0.0) > 0.0 }
		val visualCandidates = rows.count { !it.visualMatches.isNullOrEmpty() }
		val visualMatches = rows.count { it.isVerifiedMatch && it.identityMethod == "TEST_PACKAGING_APPEARANCE" }
		container.addView(TextView(this).apply {
			text = buildString {
				appendLine("ERP 출처 · $source")
				if (testReferences) {
					appendLine("원본의 상품 외관과 현재 사진을 같은 위치에서 대조합니다.")
					if (rows.isNotEmpty()) {
						val rate = if (verified == rows.size) "100" else String.format(java.util.Locale.KOREA, "%.1f", 100.0 * verified / rows.size)
						appendLine("기준 사진·위치 일치율 · $rate% ($verified/${rows.size})")
					}
					appendLine("지정한 6개 장면의 포장 외관 비교입니다. 위치는 사전 지정 영역을 사용하며 실제 상품 코드와 수량은 확인하지 않습니다.")
				}
				if (testProducts) appendLine("사람이 지정한 위치 영역 + 상품 코드 OCR · 자동 탐지 성능 평가와 구분")
				if (testRackLabels) {
					appendLine("$ocrState · 읽은 글자 ${ocrTokens.size}개 · 테스트 라벨 일치 ${verified}곳")
					appendLine("내장 테스트 이미지 · 랙 라벨을 테스트용 상품 코드로 대조")
					if (rows.isNotEmpty()) {
						val rate = if (verified == rows.size) "100" else String.format(java.util.Locale.KOREA, "%.1f", 100.0 * verified / rows.size)
						appendLine("테스트 라벨 일치율 · $rate% ($verified/${rows.size})")
					}
					if (result.model?.trainingScope?.startsWith("reference_scenes_included") == true) {
						appendLine("이 촬영 장면을 학습에 포함한 테스트 모델입니다. 새로운 사진의 성능은 별도 검증이 필요합니다.")
					}
				} else if (!testReferences) {
					appendLine("$ocrState · 읽은 글자 ${ocrTokens.size}개 · OCR 상품 후보 ${ocrIdentified}곳")
				}
				if (result.visualStatus == "completed") {
					appendLine("테스트 포장 외관 · 후보 ${visualCandidates}곳 · ERP 위치 일치 ${visualMatches}곳")
				}
				append("AI 판정 기준 · 이상 ${anomalies}건 · 추가 확인 ${rows.size - verified - anomalies}건")
			}
			setPadding(0, 0, 0, dpToPx(10))
		})

		alertDeliveryRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
		container.addView(alertDeliveryRow)
		renderAlertDelivery()
		comparisonRows = rows.sortedBy { row ->
			when {
				row.isConfirmedAnomaly -> 0
				row.isMatch -> 2
				else -> 1
			}
		}
		val positions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
		positionList = positions
		val filter = CheckBox(this).apply {
			text = "확인 필요한 결과만 보기"
			isChecked = viewModel.reviewOnly
			isEnabled = rows.isNotEmpty()
			setOnCheckedChangeListener { _, checked ->
				viewModel.reviewOnly = checked
				renderPositions()
			}
		}
		container.addView(filter)
		container.addView(positions)
		renderPositions()
	}

	private fun reviewRecords(): Map<String, ManualAuditReview> =
		if (viewModel.canSaveManualReview()) viewModel.manualReviews.value?.records.orEmpty() else emptyMap()

	private fun renderAlertDelivery() {
		val container = alertDeliveryRow ?: return
		container.removeAllViews()
		val status = viewModel.alertDelivery.value ?: return
		container.addView(TextView(this).apply {
			text = status.message
			setTextColor(if (status.canRetry) 0xFFB45309.toInt() else WarehouseAppearance.TEAL)
			setPadding(0, dpToPx(10), 0, dpToPx(12))
		})
		if (status.canRetry) addAction(container, "알림 다시 전송") { viewModel.retryAlertDelivery() }
	}

	private fun renderPositions() {
		val container = positionList ?: return
		container.removeAllViews()
		val visible = if (viewModel.reviewOnly) comparisonRows.filterNot { it.isMatch } else comparisonRows
		val records = reviewRecords()
		if (visible.isEmpty()) {
			container.addView(TextView(this).apply {
				text = if (comparisonRows.isEmpty()) "대조 가능한 위치가 없습니다." else "확인이 필요한 결과가 없습니다."
			})
		}
		for (row in visible) {
			container.addView(createComparisonCard(row, records[row.manualAuditPosition()?.id]))
		}
		val comparedIds = comparisonRows.mapNotNull { it.manualAuditPosition()?.id }.toSet()
		for (record in records.values.filter { it.position.id !in comparedIds }) {
			addAction(container, "${record.position.label} · 수동 확인 완료") {
				showManualReviewDialog(record.position)
			}
		}
	}

	private fun addAction(container: LinearLayout, label: String, action: () -> Unit) {
		container.addView(Button(this).apply {
			text = label
			setTextColor(Color.WHITE)
			background = ContextCompat.getDrawable(this@AiAuditResults, R.drawable.cr8b0d9488)
			layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(46)).apply {
				setMargins(0, 0, 0, dpToPx(10))
			}
			setOnClickListener { action() }
		})
	}

	private fun showTextDialog(
		title: String,
		contents: String,
		copy: Boolean = false,
		onManualReview: (() -> Unit)? = null
	) {
		val scroll = ScrollView(this).apply {
			setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12))
			addView(TextView(this@AiAuditResults).apply {
				text = contents
				typeface = Typeface.MONOSPACE
				textSize = 12f
			})
		}
		val dialog = AlertDialog.Builder(this).setTitle(title).setView(scroll).setNegativeButton("닫기", null)
		if (copy) dialog.setPositiveButton("클립보드에 복사") { _, _ ->
			val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
			clipboard.setPrimaryClip(android.content.ClipData.newPlainText(title, contents))
		}
		if (onManualReview != null) {
			dialog.setPositiveButton("수동 확인") { _, _ -> onManualReview() }
		}
		dialog.show()
	}

	private fun statusLabel(row: F03ComparedPosition): String = when {
		row.testProductLocationMatch && row.identityMethod == "TEST_REFERENCE_APPEARANCE" -> "기준 사진·위치 일치"
		row.testProductLocationMatch -> "비교 테스트 일치"
		row.testLabelMatch -> "테스트 라벨 일치"
		row.isVerifiedMatch && row.identityMethod == "TEST_PACKAGING_APPEARANCE" -> "테스트 외관·위치 일치"
		row.productLocationVerified && row.quantityVerified == false -> "상품·위치 일치"
		row.isVerifiedMatch -> "정상 일치"
		row.f04StatusCode == 2 -> "오적재"
		row.f04StatusCode == 3 -> "누락"
		row.f04StatusCode == 4 -> "미등록"
		else -> when (row.reviewStatus) {
			"위치매핑_확인필요", "실제창고위치_확인필요" -> "ERP 위치 연결 필요"
			"ERP위치없음_확인필요" -> "ERP 위치 등록 확인"
			"ERP조회실패_확인필요" -> "ERP 조회 실패"
			"ERP데이터_확인필요" -> "ERP 재고 데이터 확인"
			"YOLO탐지_OCR미인식_재확인", "실제상품코드_미확인" -> "상품 코드 미확인"
			"전산재고_미탐지_재확인" -> "사진에서 적재물 미탐지"
			"제품ID일치_추가검증필요", "실제상품코드_추가검증필요" -> "상품 후보 추가 확인"
			"미등록_또는_오적재의심" -> "상품 코드 불일치 후보"
			else -> "수동 확인"
		}
	}

	private fun positionLabel(row: F03ComparedPosition): String =
		row.locationLabel ?: row.floor?.let { "${row.rack} ${it}단" } ?: "위치 확인 필요"

	private fun productLabels(codes: List<String>, names: Map<String, String>?): String =
		codes.filter { it.isNotBlank() }.joinToString(" / ") { code ->
			val name = names?.get(code)
			if (name.isNullOrBlank() || name == code) code else "$name ($code)"
		}

	private fun showPositionDetails(row: F03ComparedPosition?, detection: F03Detection? = null) {
		val position = row?.manualAuditPosition()?.takeIf { detection?.locationVerified != false }
		val savedReview = position?.id?.let { reviewRecords()[it] }
		val title = row?.let { "${positionLabel(it)} · ${statusLabel(it)}" } ?: "위치 확인 필요"
		val observed = row?.let { productLabels(it.aiProductIds ?: listOf(it.aiProductId.orEmpty()), it.productNames) }.orEmpty()
		val details = buildString {
			if (row == null || !row.locationVerified) {
				appendLine("사진 속 적재물을 ERP의 랙·단 한 곳에 연결하지 못했습니다. 위치별 상품 대조를 보류했습니다.")
				if (observed.isNotBlank()) appendLine("사진에서 판독한 상품 후보 · $observed")
			} else {
				if (detection != null && !detection.locationVerified) {
					appendLine("표시된 랙·단은 후보 위치입니다. 테두리가 위치 경계를 벗어납니다.")
				}
				val expected = productLabels(row.erpProductIds.orEmpty(), row.productNames).ifBlank {
					if (row.reviewStatus == "ERP조회실패_확인필요") "조회 실패" else "등록 정보 없음"
				}
				if (row.identityMethod == "TEST_RACK_LABEL") {
					appendLine("전산 테스트 코드 · $expected")
					appendLine("사진에서 읽은 테스트 코드 · ${observed.ifBlank { "미확인" }}")
				} else {
					appendLine("ERP 등록 상품 · $expected")
					appendLine("사진에서 식별한 상품 · ${observed.ifBlank { "미확인" }}")
				}
				if (row.identityMethod in setOf("OCR_PRODUCT_CODE", "TEST_REGION_OCR_PRODUCT_CODE")) appendLine("상품 식별 · 사진 속 상품 코드 OCR")
				if (row.identityMethod == "TEST_REFERENCE_APPEARANCE") {
					appendLine("상품 비교 · 원본 기준 사진의 포장 색상과 무늬")
					row.referenceEvidence?.let { evidence ->
						appendLine("가장 가까운 기준 위치 · ${evidence.referenceLocation ?: "미확인"}")
						appendLine("외관 판독 · ${if (evidence.accepted) "기준 상품 후보 확인" else "추가 확인 필요"}")
					}
				}
				if (row.locationMethod == "human_annotated_test_region") appendLine("위치 연결 · 사람이 지정한 비교 테스트 영역")
				if (row.locationMethod == "ocr_rack_label") appendLine("위치 연결 · 사진 속 랙·단 라벨 OCR")
				appendLine("ERP 대조 · ${statusLabel(row)}")
				if (row.productLocationVerified && row.quantityVerified == false) appendLine("수량은 별도 확인이 필요합니다.")
			}
			if (row != null) {
				if (row.locationMethod != "human_annotated_test_region") appendLine("적재물 후보 · ${row.yoloCandidates}개")
				for (match in row.visualMatches.orEmpty()) {
					appendLine("포장 외관 후보 · ${match.sku} · 위치가 맞는 포장 특징 ${match.inliers}개")
				}
				row.reviewReasons.orEmpty().forEach { appendLine(it) }
			}
			if (detection != null) {
				if (detection.source == "registered_appearance") appendLine("포장 무늬와 색상으로 위치 확인")
				else detection.confidence?.let { appendLine("탐지 신뢰도 · ${(it * 100).toInt()}%") }
			}
			val ids = (row?.ocrTokenIds ?: detection?.ocrTokenIds).orEmpty().toSet()
			val evidence = ocrTokens.filter { it.id in ids }
			appendLine()
			if (row?.identityMethod != "TEST_REFERENCE_APPEARANCE") {
				appendLine("이 영역에서 OCR로 읽은 글자")
				if (evidence.isEmpty()) appendLine("표시할 판독 글자가 없습니다.")
				for (token in evidence) appendLine(ocrLabel(token))
			}
			if (savedReview != null) {
				appendLine()
				appendLine("수동 확인 완료")
				appendLine("실물 바코드 · ${savedReview.values.barcode}")
				appendLine("실측 수량 · ${savedReview.values.quantity}")
			}
		}
		val manualAction: (() -> Unit)? = if (viewModel.canSaveManualReview()) {
			{ if (position == null) selectManualPosition() else showManualReviewDialog(position) }
		} else null
		showTextDialog(title, details, onManualReview = manualAction)
	}

	private fun selectManualPosition() {
		val loading = AlertDialog.Builder(this)
			.setMessage("창고의 전산 위치를 불러오는 중입니다.")
			.setNegativeButton("취소", null)
			.show()
		viewModel.getManualPositions { positions ->
			if (isFinishing || isDestroyed || !loading.isShowing) return@getManualPositions
			loading.dismiss()
			if (positions.isEmpty()) {
				Toast.makeText(this, "선택할 수 있는 전산 위치를 확인하지 못했습니다.", Toast.LENGTH_LONG).show()
				return@getManualPositions
			}
			val duplicateLabels = positions.groupingBy { it.label }.eachCount()
			val labels = positions.map {
				if (duplicateLabels.getValue(it.label) > 1) "${it.label} (${it.id})" else it.label
			}
			WarehouseMenus.show(this, "실물에서 확인한 랙·단 선택", labels) { index -> showManualReviewDialog(positions[index]) }
		}
	}

	private fun showManualReviewDialog(position: ManualAuditPosition) {
		if (!viewModel.canSaveManualReview()) return
		val existing = reviewRecords()[position.id]?.values
		val barcode = EditText(this).apply {
			hint = "실물 바코드"
			contentDescription = "실물 바코드"
			inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
			setSingleLine(true)
			filters = arrayOf(InputFilter.LengthFilter(256))
			setText(existing?.barcode.orEmpty())
		}
		val quantity = EditText(this).apply {
			hint = "실측 수량"
			contentDescription = "실측 수량"
			inputType = InputType.TYPE_CLASS_NUMBER
			filters = arrayOf(InputFilter.LengthFilter(10))
			setText(existing?.quantity?.toString().orEmpty())
		}
		val fields = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(dpToPx(24), dpToPx(8), dpToPx(24), dpToPx(8))
			addView(barcode)
			addView(quantity)
		}
		val dialog = AlertDialog.Builder(this)
			.setTitle("${position.label} · 수동 확인")
			.setView(fields)
			.setNegativeButton("취소", null)
			.setPositiveButton("기기에 저장", null)
			.show()
		val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
		fun readInput() = manualAuditValues(barcode.text.toString(), quantity.text.toString())
		var saving = false
		fun updateSaveButton() {
			saveButton.isEnabled = !saving && readInput() != null
		}
		barcode.doAfterTextChanged { updateSaveButton() }
		quantity.doAfterTextChanged { updateSaveButton() }
		updateSaveButton()
		saveButton.setOnClickListener {
			val values = readInput() ?: return@setOnClickListener
			saving = true
			updateSaveButton()
			viewModel.saveManualReview(position, values.barcode, values.quantity.toString()) { saved ->
				if (isFinishing || isDestroyed || !dialog.isShowing) return@saveManualReview
				if (saved) {
					dialog.dismiss()
					Toast.makeText(this, "수동 확인 결과를 기기에 저장했습니다.", Toast.LENGTH_SHORT).show()
				} else {
					saving = false
					updateSaveButton()
					Toast.makeText(this, "수동 확인 결과를 저장하지 못했습니다.", Toast.LENGTH_LONG).show()
				}
			}
		}
	}

	private fun ocrLabel(token: F03OcrToken): String {
		val kind = when (token.kind) {
			"product" -> "상품 후보"
			"location_label" -> "랙 라벨 형태"
			else -> "기타 글자"
		}
		return "${token.text} · $kind · ${(token.confidence * 100).toInt()}%"
	}

	private fun createComparisonCard(row: F03ComparedPosition, review: ManualAuditReview?): View {
		val backgroundId = when {
			row.isMatch -> R.drawable.s10b981sw1cr12becfdf5
			row.isConfirmedAnomaly -> R.drawable.sef4444sw1cr12bfef2f2
			else -> R.drawable.sf59e0bsw1cr12bfffbeb
		}
		return TextView(this).apply {
			val evidence = when {
				row.identityMethod == "TEST_REFERENCE_APPEARANCE" -> "기준 사진 상품 외관 비교"
				row.locationMethod == "human_annotated_test_region" -> "지정 영역 상품 대조"
				else -> "사진·전산 상품 대조"
			}
			text = "${positionLabel(row)} · ${statusLabel(row)}\n$evidence · 상세 보기"
			if (review != null) append("\n수동 확인 완료 · 실측 ${review.values.quantity}개")
			setPadding(dpToPx(14), dpToPx(12), dpToPx(14), dpToPx(12))
			setTextColor(Color.parseColor("#0F172A"))
			background = ContextCompat.getDrawable(this@AiAuditResults, backgroundId)
			layoutParams = LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
			).apply { setMargins(0, 0, 0, dpToPx(10)) }
			setOnClickListener { showPositionDetails(row) }
		}
	}

	private fun dpToPx(dp: Int): Int {
		return (dp * resources.displayMetrics.density).toInt()
	}

}
