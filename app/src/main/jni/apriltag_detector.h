#pragma once

#include <opencv2/core/core.hpp>
#include <vector>
#include <string>

// One detected AprilTag
struct AprilTagDetection
{
    int   id;           // Tag ID within the family
    float cx, cy;       // Centre of the tag (pixels)
    float corners[4][2]; // Four corners [idx][x or y], counter-clockwise from bottom-left
};

// Wraps the apriltag C library detector.
// Create once (e.g. at JNI_OnLoad) and reuse every frame.
class AprilTagDetector
{
public:
    AprilTagDetector();
    ~AprilTagDetector();

    // Detect tags in a grayscale (CV_8UC1) image.
    void detect(const cv::Mat& gray, std::vector<AprilTagDetection>& detections) const;

    // Draw tag outlines and IDs onto an RGB/BGR frame.
    void draw(cv::Mat& rgb, const std::vector<AprilTagDetection>& detections) const;

    // Serialise detections into a JSON array string.
    // Coordinates are normalized to [0,1] when image dimensions are provided.
    // Example: [{"id":5,"cx":0.5000,"cy":0.2500,"corners":[[…],[…],[…],[…]]}]
    static std::string toJson(const std::vector<AprilTagDetection>& detections,
                              int imageWidth = 0,
                              int imageHeight = 0);

private:
    void* m_td; // apriltag_detector_t*, opaque to callers
    void* m_tf; // apriltag_family_t* (tag36h11)
};
