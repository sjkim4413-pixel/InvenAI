#include <jni.h>
#include <opencv2/opencv.hpp>
#include <vector>
#include <algorithm>
#include <android/log.h>

#define LOG_TAG "VisionProcessor"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// 4개의 모서리를 좌상, 우상, 우하, 좌하 순서로 정렬하는 헬퍼 함수
std::vector<cv::Point2f> orderPoints(const std::vector<cv::Point2f>& pts) {
    std::vector<cv::Point2f> ordered(4);
    std::vector<float> sum(4), diff(4);

    for (int i = 0; i < 4; i++) {
        sum[i] = pts[i].x + pts[i].y;
        diff[i] = pts[i].y - pts[i].x;
    }
    ordered[0] = pts[std::distance(sum.begin(), std::min_element(sum.begin(), sum.end()))];
    ordered[2] = pts[std::distance(sum.begin(), std::max_element(sum.begin(), sum.end()))];
    ordered[1] = pts[std::distance(diff.begin(), std::min_element(diff.begin(), diff.end()))];
    ordered[3] = pts[std::distance(diff.begin(), std::max_element(diff.begin(), diff.end()))];
    return ordered;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_myapp_EdgeVisionManager_getOpenCVVersion(JNIEnv* env, jobject /* this */) {
    return env->NewStringUTF(cv::getVersionString().c_str());
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_myapp_EdgeVisionManager_processFrameNative(JNIEnv* env, jobject /* this */, jbyteArray photoBytes) try {
    jsize len = env->GetArrayLength(photoBytes);
    if (len == 0) return nullptr;

    std::vector<uchar> imgData(len);
    env->GetByteArrayRegion(photoBytes, 0, len, reinterpret_cast<jbyte*>(imgData.data()));
    if (env->ExceptionCheck()) return nullptr;
    cv::Mat src = cv::imdecode(imgData, cv::IMREAD_COLOR | cv::IMREAD_IGNORE_ORIENTATION);

    if (src.empty()) return nullptr;

    // 1. 흔들림 감지 (Laplacian 분산값)
    cv::Mat gray, laplacian;
    cv::cvtColor(src, gray, cv::COLOR_BGR2GRAY);
    cv::Laplacian(gray, laplacian, CV_64F);

    cv::Scalar mean, stddev;
    cv::meanStdDev(laplacian, mean, stddev);
    double variance = stddev.val[0] * stddev.val[0];

    if (variance < 80.0) {
        LOGD("Blur detected. Discarding image.");
        return nullptr;
    }

    // 2. 모서리 자동 탐색 (가장 큰 사각형 찾기)
    cv::Mat blurred, edges;
    cv::GaussianBlur(gray, blurred, cv::Size(5, 5), 0);
    cv::Canny(blurred, edges, 30, 120);

    std::vector<std::vector<cv::Point>> contours;
    cv::findContours(edges, contours, cv::RETR_EXTERNAL, cv::CHAIN_APPROX_SIMPLE);

    std::sort(contours.begin(), contours.end(), [](const std::vector<cv::Point>& a, const std::vector<cv::Point>& b) {
        return cv::contourArea(a) > cv::contourArea(b);
    });

    std::vector<cv::Point> approx;
    std::vector<cv::Point2f> srcPts;

    // A package face is also rectangular. Limit correction to a contour
    // covering most of the frame so surrounding rack positions are preserved.
    const double minRackArea = static_cast<double>(src.cols) * src.rows * 0.8;
    for (const auto& contour : contours) {
        if (cv::contourArea(contour) < minRackArea) break;
        double peri = cv::arcLength(contour, true);
        cv::approxPolyDP(contour, approx, 0.04 * peri, true);

        if (approx.size() == 4 && cv::isContourConvex(approx) && cv::contourArea(approx) >= minRackArea) {
            srcPts.assign(approx.begin(), approx.end());
            break;
        }
    }


    float destWidth, destHeight;
    std::vector<cv::Point2f> dstPts;

    // 3. 비율 유지 동적 리사이징 및 왜곡 방어(Sanity Check)
    if (!srcPts.empty()) {
        srcPts = orderPoints(srcPts);

        float widthA = cv::norm(srcPts[2] - srcPts[3]); // 하단 변 길이
        float widthB = cv::norm(srcPts[1] - srcPts[0]); // 상단 변 길이
        destWidth = std::max(widthA, widthB);

        float heightA = cv::norm(srcPts[1] - srcPts[2]); // 우측 변 길이
        float heightB = cv::norm(srcPts[0] - srcPts[3]); // 좌측 변 길이
        destHeight = std::max(heightA, heightB);

        if (destWidth < 64.0f || destHeight < 64.0f ||
            destWidth < 0.3f * destHeight || destWidth > 3.0f * destHeight ||
            !cv::isContourConvex(srcPts)) {
            return photoBytes;
        }
    }

    if (srcPts.empty()) return photoBytes;

    dstPts = {
            cv::Point2f(0, 0),
            cv::Point2f(destWidth, 0),
            cv::Point2f(destWidth, destHeight),
            cv::Point2f(0, destHeight)
    };

    // 5. 투시 변환 적용
    cv::Mat transformMatrix = cv::getPerspectiveTransform(srcPts, dstPts);
    cv::Mat warped;
    cv::warpPerspective(src, warped, transformMatrix, cv::Size(destWidth, destHeight));

    // 6. 변환된 이미지를 JPEG 바이트 배열로 인코딩하여 반환
    std::vector<uchar> outBuffer;
    if (!cv::imencode(".jpg", warped, outBuffer, {cv::IMWRITE_JPEG_QUALITY, 95})) {
        return photoBytes;
    }

    jbyteArray result = env->NewByteArray(outBuffer.size());
    if (result == nullptr) return nullptr;
    env->SetByteArrayRegion(result, 0, outBuffer.size(), (jbyte*)outBuffer.data());

    return result;
} catch (const cv::Exception& error) {
    LOGD("Image preprocessing failed: %s", error.what());
    return nullptr;
}
