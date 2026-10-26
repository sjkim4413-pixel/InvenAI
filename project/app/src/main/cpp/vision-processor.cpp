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
Java_com_example_myapp_EdgeVisionManager_processFrameNative(JNIEnv* env, jobject /* this */, jbyteArray photoBytes) {
    jsize len = env->GetArrayLength(photoBytes);
    if (len == 0) return nullptr;

    jbyte* buffer = env->GetByteArrayElements(photoBytes, nullptr);
    std::vector<uchar> imgData(buffer, buffer + len);
    cv::Mat src = cv::imdecode(imgData, cv::IMREAD_COLOR);
    env->ReleaseByteArrayElements(photoBytes, buffer, JNI_ABORT);

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

    std::vector<cv::Point2f> approx;
    std::vector<cv::Point2f> srcPts;

    for (const auto& contour : contours) {
        if (cv::contourArea(contour) < 5000) break;
        double peri = cv::arcLength(contour, true);
        cv::approxPolyDP(contour, approx, 0.04 * peri, true);

        if (approx.size() == 4) {
            srcPts = approx;
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

        float ratio = destWidth / destHeight;
        if (ratio < 0.3f || ratio > 3.0f) {
            LOGD("Extreme distortion detected (ratio: %f). Falling back.", ratio);
            srcPts.clear(); // 극단적 비율일 경우 오인식으로 간주하여 강제 폴백
        } else {
            LOGD("Dynamic contour matched. Width: %f, Height: %f", destWidth, destHeight);
        }
    }

    // 4. 모서리 탐색 실패 또는 극단적 왜곡 시 중앙 AR 가이드 고정 영역으로 폴백
    if (srcPts.empty()) {
        LOGD("Falling back to fixed center guide.");
        int width = src.cols;
        int height = src.rows;
        destWidth = width * 0.8f;
        destHeight = destWidth * (4.0f / 3.0f); // 3:4 고정 비율

        float startX = (width - destWidth) / 2.0f;
        float startY = (height - destHeight) / 2.0f;
        srcPts = {
                cv::Point2f(startX, startY),
                cv::Point2f(startX + destWidth, startY),
                cv::Point2f(startX + destWidth, startY + destHeight),
                cv::Point2f(startX, startY + destHeight)
        };
    }

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
    cv::imencode(".jpg", warped, outBuffer);

    jbyteArray result = env->NewByteArray(outBuffer.size());
    env->SetByteArrayRegion(result, 0, outBuffer.size(), (jbyte*)outBuffer.data());

    return result;
}