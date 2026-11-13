"""Geometry and product-ID checks shared by inference and ERP comparison."""
import math
import re


def product_ids(value):
    values = value if isinstance(value, (list, tuple)) else re.split(r'[,;\n]', str(value or ''))
    return [str(v).strip() for v in values if str(v).strip().lower() not in {'', '0', '0.0', 'nan', 'none', 'null'}]


def covered_fraction(inner, outer):
    x1, y1, x2, y2 = map(float, inner)
    a1, b1, a2, b2 = map(float, outer)
    if not all(math.isfinite(v) for v in (x1,y1,x2,y2,a1,b1,a2,b2)):
        return 0.0
    area = max(0,x2-x1)*max(0,y2-y1)
    intersection = max(0,min(x2,a2)-max(x1,a1))*max(0,min(y2,b2)-max(y1,b1))
    return intersection/area if area else 0.0


def spatially_verified(label_box, object_box, floor_box, all_object_boxes):
    return (
        covered_fraction(object_box, floor_box) >= .95
        and covered_fraction(label_box, object_box) >= .95
        and sum(covered_fraction(label_box, box) >= .5 for box in all_object_boxes) == 1
    )
