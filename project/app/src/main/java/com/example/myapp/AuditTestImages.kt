package com.example.myapp

import com.google.gson.Gson

internal data class AuditTestImage(
    val file: String,
    val title: String,
    val group: String,
    val mode: String,
    val testRackLabels: Boolean,
    val warehouseCode: String?,
    val pairId: String? = null,
    val variant: String? = null
)

private data class AuditTestImageManifest(val version: Int = 0, val images: List<AuditTestImageEntry>? = null)
private data class AuditTestImageEntry(
    val file: String? = null,
    val title: String? = null,
    val group: String? = null,
    val mode: String? = null,
    val warehouseCode: String? = null,
    val enabled: Boolean = true,
    val pairId: String? = null,
    val variant: String? = null
)

internal fun parseAuditTestImages(json: String, availableFiles: List<String>): List<AuditTestImage> {
    val manifest = Gson().fromJson(json, AuditTestImageManifest::class.java)
    require(manifest != null && manifest.version == 1) { "테스트 이미지 목록 형식을 확인해 주세요." }
    val entries = requireNotNull(manifest.images)
    require(entries.isNotEmpty()) { "등록된 테스트 이미지가 없습니다." }
    val result = entries.map { entry ->
        val file = requireNotNull(entry.file)
        val title = requireNotNull(entry.title)
        require(file.matches(Regex("[A-Za-z0-9_-]+\\.png")) && file in availableFiles)
        require(title.isNotBlank() && title == title.trim())
        require(entry.group in setOf("comparison", "reference", "paired"))
        require(entry.mode in setOf("live", "test_rack_labels", "test_product_regions", "test_reference_pairs"))
        if (entry.mode != "test_rack_labels") require(!entry.warehouseCode.isNullOrBlank())
        if (entry.mode == "test_reference_pairs") {
            require(entry.group == "paired" && entry.pairId?.matches(Regex("pair_[0-9]{2}")) == true)
            require(entry.variant in setOf("normal", "misplaced"))
        }
        AuditTestImage(file, title, requireNotNull(entry.group), requireNotNull(entry.mode), entry.mode == "test_rack_labels", entry.warehouseCode, entry.pairId, entry.variant)
    }
    require(result.map { it.file }.toSet().size == result.size) { "테스트 사진이 중복 등록됐습니다." }
    require(result.map { it.title }.toSet().size == result.size) { "테스트 제목이 중복 등록됐습니다." }
    require(result.map { it.file }.toSet() == availableFiles.toSet()) { "테스트 사진과 목록이 일치하지 않습니다." }
    val active = result.filterIndexed { index, _ -> entries[index].enabled }
    require(active.isNotEmpty()) { "선택할 수 있는 테스트 사진이 없습니다." }
    active.filter { it.mode == "test_reference_pairs" }.groupBy { it.pairId }.values.forEach { pair ->
        require(pair.size == 2 && pair.map { it.variant }.toSet() == setOf("normal", "misplaced"))
        require(pair.map { it.warehouseCode }.toSet().size == 1)
    }
    return active
}
