from fastapi import APIRouter
from pydantic import BaseModel, Field

router = APIRouter(prefix="/f04", tags=["F04 Safe Review"])


class DetectionItem(BaseModel):
    location: str
    product_id: str | None = None
    detection_state: str = "unreadable"


class ReviewRequest(BaseModel):
    detections: list[DetectionItem] = Field(default_factory=list)


@router.post("/safe-review")
def safe_review(request: ReviewRequest):
    results = []

    for item in request.detections:
        results.append({
            "location": item.location,
            "ai_product_id": item.product_id,
            "review_status": "MANUAL_REVIEW_REQUIRED",
            "verification_passed": False,
            "reason": "회사·창고 권한 및 위치 매핑 미검증",
        })

    return {
        "module": "F04",
        "status": "review_required",
        "total_positions": len(results),
        "results": results,
    }
