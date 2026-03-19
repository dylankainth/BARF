#include "apriltag_detector.h"

// apriltag C library headers (found via target_include_directories in CMakeLists)
#include "apriltag.h"
#include "tag36h11.h"
#include "common/zarray.h"

#include <opencv2/imgproc/imgproc.hpp>
#include <android/log.h>
#include <cstdio>
#include <cstring>

static const char* TAG = "AprilTagDetector";

// ---------------------------------------------------------------------------
// Construction / destruction
// ---------------------------------------------------------------------------

AprilTagDetector::AprilTagDetector()
    : m_td(nullptr), m_tf(nullptr)
{
    apriltag_family_t* tf = tag36h11_create();

    apriltag_detector_t* td = apriltag_detector_create();
    apriltag_detector_add_family(td, tf);

    // quad_decimate: 2.0 halves each dimension before quad detection —
    // faster on mobile, moderate accuracy trade-off.
    td->quad_decimate  = 2.0f;
    td->quad_sigma     = 0.0f;  // no Gaussian pre-blur
    td->nthreads       = 1;     // keep single-threaded to avoid Android thread issues
    td->debug          = 0;
    td->refine_edges   = 1;     // sub-pixel edge refinement

    m_td = td;
    m_tf = tf;

    __android_log_print(ANDROID_LOG_INFO, TAG,
        "AprilTagDetector created (tag36h11, quad_decimate=2)");
}

AprilTagDetector::~AprilTagDetector()
{
    if (m_td)
    {
        apriltag_detector_destroy(static_cast<apriltag_detector_t*>(m_td));
        m_td = nullptr;
    }
    if (m_tf)
    {
        tag36h11_destroy(static_cast<apriltag_family_t*>(m_tf));
        m_tf = nullptr;
    }
}

// ---------------------------------------------------------------------------
// Detection
// ---------------------------------------------------------------------------

void AprilTagDetector::detect(const cv::Mat& gray,
                               std::vector<AprilTagDetection>& detections) const
{
    detections.clear();

    if (gray.empty() || gray.type() != CV_8UC1)
    {
        __android_log_print(ANDROID_LOG_WARN, TAG,
            "detect: need CV_8UC1 input (%d rows x %d cols, type=%d)",
            gray.rows, gray.cols, gray.type());
        return;
    }

    // Wrap the OpenCV Mat in an apriltag image_u8_t — zero-copy, shared buffer.
    image_u8_t img = {
        .width = (int32_t)gray.cols,
        .height = (int32_t)gray.rows,
        .stride = (int32_t)gray.step[0],
        .buf = gray.data
    };

    apriltag_detector_t* td = static_cast<apriltag_detector_t*>(m_td);
    zarray_t* raw = apriltag_detector_detect(td, &img);

    const int n = zarray_size(raw);
    detections.reserve(n);

    for (int i = 0; i < n; i++)
    {
        apriltag_detection_t* det;
        zarray_get(raw, i, &det);

        AprilTagDetection d;
        d.id = det->id;
        d.cx = (float)det->c[0];
        d.cy = (float)det->c[1];
        for (int j = 0; j < 4; j++)
        {
            d.corners[j][0] = (float)det->p[j][0];
            d.corners[j][1] = (float)det->p[j][1];
        }
        detections.push_back(d);

        __android_log_print(ANDROID_LOG_DEBUG, TAG,
            "Tag id=%d  centre=(%.1f, %.1f)", d.id, d.cx, d.cy);
    }

    apriltag_detections_destroy(raw);
}

// ---------------------------------------------------------------------------
// Drawing
// ---------------------------------------------------------------------------

void AprilTagDetector::draw(cv::Mat& rgb,
                             const std::vector<AprilTagDetection>& detections) const
{
    for (const auto& d : detections)
    {
        // Draw quadrilateral outline in green
        for (int j = 0; j < 4; j++)
        {
            cv::Point2f p1(d.corners[j][0],           d.corners[j][1]);
            cv::Point2f p2(d.corners[(j + 1) % 4][0], d.corners[(j + 1) % 4][1]);
            cv::line(rgb, p1, p2, cv::Scalar(0, 255, 0), 2);
        }

        // Corner 0 (bottom-left by apriltag convention) in blue
        cv::circle(rgb,
            cv::Point2f(d.corners[0][0], d.corners[0][1]),
            6, cv::Scalar(255, 0, 0), -1);

        // Centre dot in red
        cv::circle(rgb,
            cv::Point2f(d.cx, d.cy),
            5, cv::Scalar(0, 0, 255), -1);

        // ID label above centre
        char text[32];
        snprintf(text, sizeof(text), "AT#%d", d.id);

        int baseLine = 0;
        cv::Size sz = cv::getTextSize(text, cv::FONT_HERSHEY_SIMPLEX, 0.6, 1, &baseLine);
        cv::Point tl((int)(d.cx - sz.width / 2) - 2,
                     (int)(d.cy) - 12 - sz.height - 2);
        cv::rectangle(rgb,
            cv::Rect(tl, cv::Size(sz.width + 4, sz.height + baseLine + 4)),
            cv::Scalar(0, 0, 0), -1);
        cv::putText(rgb, text,
            cv::Point(tl.x + 2, tl.y + sz.height + 1),
            cv::FONT_HERSHEY_SIMPLEX, 0.6, cv::Scalar(0, 255, 0), 1);
    }
}

// ---------------------------------------------------------------------------
// JSON serialisation
// ---------------------------------------------------------------------------

/*static*/
std::string AprilTagDetector::toJson(const std::vector<AprilTagDetection>& detections,
                                       int imageWidth,
                                       int imageHeight)
{
    auto normX = [&](float x) {
        if (imageWidth <= 0) return x;
        return std::max(0.f, std::min(1.f, x / (float)imageWidth));
    };
    auto normY = [&](float y) {
        if (imageHeight <= 0) return y;
        return std::max(0.f, std::min(1.f, y / (float)imageHeight));
    };

    std::string json = "[";
    for (size_t i = 0; i < detections.size(); i++)
    {
        const auto& d = detections[i];
        float cx = normX(d.cx);
        float cy = normY(d.cy);
        float c00 = normX(d.corners[0][0]);
        float c01 = normY(d.corners[0][1]);
        float c10 = normX(d.corners[1][0]);
        float c11 = normY(d.corners[1][1]);
        float c20 = normX(d.corners[2][0]);
        float c21 = normY(d.corners[2][1]);
        float c30 = normX(d.corners[3][0]);
        float c31 = normY(d.corners[3][1]);

        char buf[256];
        snprintf(buf, sizeof(buf),
            "{\"id\":%d,\"cx\":%.4f,\"cy\":%.4f,"
            "\"corners\":[[%.4f,%.4f],[%.4f,%.4f],[%.4f,%.4f],[%.4f,%.4f]]}",
            d.id, cx, cy,
            c00, c01,
            c10, c11,
            c20, c21,
            c30, c31);
        json += buf;
        if (i + 1 < detections.size())
            json += ",";
    }
    json += "]";
    return json;
}
