// Tencent is pleased to support the open source community by making ncnn available.
//
// Copyright (C) 2025 THL A29 Limited, a Tencent company. All rights reserved.
//
// Licensed under the BSD 3-Clause License (the "License"); you may not use this file except
// in compliance with the License. You may obtain a copy of the License at
//
// https://opensource.org/licenses/BSD-3-Clause
//
// Unless required by applicable law or agreed to in writing, software distributed
// under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
// CONDITIONS OF ANY KIND, either express or implied. See the License for the
// specific language governing permissions and limitations under the License.

#include <android/asset_manager_jni.h>
#include <android/native_window_jni.h>
#include <android/native_window.h>

#include <android/log.h>

#include <jni.h>

#include <string>
#include <vector>
#include <atomic>
#include <algorithm>

#include <platform.h>
#include <benchmark.h>

#include "yolo11.h"
#include "apriltag_detector.h"

#include "ndkcamera.h"

#include <opencv2/core/core.hpp>
#include <opencv2/imgproc/imgproc.hpp>

#if __ARM_NEON
#include <arm_neon.h>
#endif // __ARM_NEON

static int draw_unsupported(cv::Mat& rgb)
{
    const char text[] = "unsupported";

    int baseLine = 0;
    cv::Size label_size = cv::getTextSize(text, cv::FONT_HERSHEY_SIMPLEX, 1.0, 1, &baseLine);

    int y = (rgb.rows - label_size.height) / 2;
    int x = (rgb.cols - label_size.width) / 2;

    cv::rectangle(rgb, cv::Rect(cv::Point(x, y), cv::Size(label_size.width, label_size.height + baseLine)),
                    cv::Scalar(255, 255, 255), -1);

    cv::putText(rgb, text, cv::Point(x, y + label_size.height),
                cv::FONT_HERSHEY_SIMPLEX, 1.0, cv::Scalar(0, 0, 0));

    return 0;
}

static int draw_fps(cv::Mat& rgb)
{
    // resolve moving average
    float avg_fps = 0.f;
    {
        static double t0 = 0.f;
        static float fps_history[10] = {0.f};

        double t1 = ncnn::get_current_time();
        if (t0 == 0.f)
        {
            t0 = t1;
            return 0;
        }

        float fps = 1000.f / (t1 - t0);
        t0 = t1;

        for (int i = 9; i >= 1; i--)
        {
            fps_history[i] = fps_history[i - 1];
        }
        fps_history[0] = fps;

        if (fps_history[9] == 0.f)
        {
            return 0;
        }

        for (int i = 0; i < 10; i++)
        {
            avg_fps += fps_history[i];
        }
        avg_fps /= 10.f;
    }

    char text[32];
    sprintf(text, "FPS=%.2f", avg_fps);

    int baseLine = 0;
    cv::Size label_size = cv::getTextSize(text, cv::FONT_HERSHEY_SIMPLEX, 0.5, 1, &baseLine);

    int y = 0;
    int x = rgb.cols - label_size.width;

    cv::rectangle(rgb, cv::Rect(cv::Point(x, y), cv::Size(label_size.width, label_size.height + baseLine)),
                    cv::Scalar(255, 255, 255), -1);

    cv::putText(rgb, text, cv::Point(x, y + label_size.height),
                cv::FONT_HERSHEY_SIMPLEX, 0.5, cv::Scalar(0, 0, 0));

    return 0;
}

static YOLO11* g_yolo11 = 0;
static ncnn::Mutex lock;
static ncnn::Mutex g_callback_lock;
static std::atomic<int> g_display_rotation{0};
static std::atomic<bool> g_yolo_enabled{false};
static std::atomic<bool> g_apriltag_enabled{false};
// JavaVM pointer stored so native thread can call back into Java
static JavaVM* g_jvm_global = nullptr;
// Global reference to MainActivity class (set via registerActivity)
static jclass g_main_activity_class_global = nullptr;
// AprilTag detector — created once, reused every frame
static AprilTagDetector* g_apriltag = nullptr;

class MyNdkCamera : public NdkCameraWindow
{
public:
    virtual void on_image_render(cv::Mat& rgb) const;
};

void MyNdkCamera::on_image_render(cv::Mat& rgb) const
{
    // apply display rotation to the incoming frame if requested
    int rot = g_display_rotation.load();
    if (rot == 90)
    {
        cv::Mat tmp;
        cv::transpose(rgb, tmp);
        cv::flip(tmp, tmp, 1); // rotate 90 CW
        rgb = tmp;
    }
    else if (rot == 270)
    {
        cv::Mat tmp;
        cv::transpose(rgb, tmp);
        cv::flip(tmp, tmp, 0); // rotate 90 CCW
        rgb = tmp;
    }

    std::vector<Object> yolo_objects;
    std::vector<AprilTagDetection> atags;

    // ---- YOLO11 detection ------------------------------------------------
    if (g_yolo_enabled.load())
    {
        ncnn::MutexLockGuard g(lock);

        if (g_yolo11)
        {
            g_yolo11->detect(rgb, yolo_objects);
            g_yolo11->draw(rgb, yolo_objects);
        }
        else
        {
            draw_unsupported(rgb);
        }
    }

    // ---- AprilTag detection (independent of YOLO lock) -------------------
    if (g_apriltag_enabled.load() && g_apriltag)
    {
        cv::Mat gray;
        cv::cvtColor(rgb, gray, CV_BGR2GRAY);
        g_apriltag->detect(gray, atags);
        g_apriltag->draw(rgb, atags);
    }

    // ---- Broadcast combined JSON when anything is detected ---------------
    if ((!yolo_objects.empty() || !atags.empty()) && g_jvm_global != nullptr)
    {
        // Build "yolo" array
        std::string yolo_json = "[";
        for (size_t i = 0; i < yolo_objects.size(); i++)
        {
            const Object& o = yolo_objects[i];
            const float xn = rgb.cols > 0 ? std::max(0.f, std::min(1.f, o.rect.x / (float)rgb.cols)) : 0.f;
            const float yn = rgb.rows > 0 ? std::max(0.f, std::min(1.f, o.rect.y / (float)rgb.rows)) : 0.f;
            const float wn = rgb.cols > 0 ? std::max(0.f, std::min(1.f, o.rect.width / (float)rgb.cols)) : 0.f;
            const float hn = rgb.rows > 0 ? std::max(0.f, std::min(1.f, o.rect.height / (float)rgb.rows)) : 0.f;
            char buf[256];
            snprintf(buf, sizeof(buf),
                "{\"label\":%d,\"x\":%.4f,\"y\":%.4f,\"w\":%.4f,\"h\":%.4f,\"score\":%.4f}",
                o.label, xn, yn, wn, hn, o.prob);
            yolo_json += buf;
            if (i + 1 < yolo_objects.size()) yolo_json += ",";
        }
        yolo_json += "]";

        // Combined payload: {"yolo":[...], "apriltags":[...]}
        std::string json = "{\"yolo\":" + yolo_json
            + ",\"apriltags\":" + AprilTagDetector::toJson(atags, rgb.cols, rgb.rows) + "}";

        // Attach to JVM and call MainActivity.pushDetectionsToScripts
        JNIEnv* env = nullptr;
        bool attached = false;
        if (g_jvm_global->GetEnv((void**)&env, JNI_VERSION_1_4) == JNI_EDETACHED)
        {
            if (g_jvm_global->AttachCurrentThread(&env, NULL) == 0)
                attached = true;
            else
                env = nullptr;
        }

        jclass activity_cls = nullptr;
        if (env)
        {
            ncnn::MutexLockGuard cb_guard(g_callback_lock);
            if (g_main_activity_class_global != nullptr)
            {
                activity_cls = (jclass)env->NewLocalRef(g_main_activity_class_global);
            }
        }

        if (env && activity_cls != nullptr)
        {
            jmethodID mid = env->GetStaticMethodID(activity_cls,
                "pushDetectionsToScripts", "(Ljava/lang/String;)V");
            if (mid)
            {
                jstring jstr = env->NewStringUTF(json.c_str());
                env->CallStaticVoidMethod(activity_cls, mid, jstr);
                env->DeleteLocalRef(jstr);
            }
            env->DeleteLocalRef(activity_cls);
        }

        if (env && attached)
            g_jvm_global->DetachCurrentThread();
    }

    draw_fps(rgb);
}

static MyNdkCamera* g_camera = 0;

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved)
{
    __android_log_print(ANDROID_LOG_DEBUG, "ncnn", "JNI_OnLoad");

    g_camera = new MyNdkCamera;

    // store JavaVM for callbacks
    g_jvm_global = vm;

    // create AprilTag detector once; reused for every frame
    g_apriltag = new AprilTagDetector;

    ncnn::create_gpu_instance();

    return JNI_VERSION_1_4;
}

// Java binding: register the Activity instance for callbacks
JNIEXPORT void JNICALL Java_com_tencent_yolo11ncnn_YOLO11Ncnn_registerActivity(JNIEnv* env, jobject thiz, jobject activity)
{
    __android_log_print(ANDROID_LOG_DEBUG, "ncnn", "registerActivity called");
    ncnn::MutexLockGuard cb_guard(g_callback_lock);

    if (g_main_activity_class_global != nullptr)
    {
        env->DeleteGlobalRef(g_main_activity_class_global);
        g_main_activity_class_global = nullptr;
    }

    if (activity != nullptr)
    {
        jclass cls = env->GetObjectClass(activity);
        if (cls != nullptr)
        {
            g_main_activity_class_global = (jclass)env->NewGlobalRef(cls);
            env->DeleteLocalRef(cls);
        }
    }
}

JNIEXPORT void JNI_OnUnload(JavaVM* vm, void* reserved)
{
    __android_log_print(ANDROID_LOG_DEBUG, "ncnn", "JNI_OnUnload");

    {
        ncnn::MutexLockGuard g(lock);

        delete g_yolo11;
        g_yolo11 = 0;
    }

    delete g_apriltag;
    g_apriltag = nullptr;

    ncnn::destroy_gpu_instance();

    delete g_camera;
    g_camera = 0;
    if (g_main_activity_class_global != nullptr)
    {
        JNIEnv* env = nullptr;
        if (g_jvm_global && g_jvm_global->GetEnv((void**)&env, JNI_VERSION_1_4) == JNI_OK)
        {
            ncnn::MutexLockGuard cb_guard(g_callback_lock);
            env->DeleteGlobalRef(g_main_activity_class_global);
        }
        g_main_activity_class_global = nullptr;
    }
}

// public native boolean loadModel(AssetManager mgr, int taskid, int modelid, int cpugpu);
JNIEXPORT jboolean JNICALL Java_com_tencent_yolo11ncnn_YOLO11Ncnn_loadModel(JNIEnv* env, jobject thiz, jobject assetManager, jint taskid, jint modelid, jint cpugpu)
{
    if (taskid < 0 || taskid > 4 || modelid < 0 || modelid > 8 || cpugpu < 0 || cpugpu > 2)
    {
        return JNI_FALSE;
    }

    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);

    __android_log_print(ANDROID_LOG_DEBUG, "ncnn", "loadModel %p", mgr);

    const char* tasknames[5] =
    {
        "",
        "_seg",
        "_pose",
        "_cls",
        "_obb"
    };

    const char* modeltypes[9] =
    {
        "n",
        "s",
        "m",
        "n",
        "s",
        "m",
        "n",
        "s",
        "m"
    };

    std::string yoloname = "yolo11";
    if (taskid==0){
        yoloname = "yolo26";
    }
    std::string parampath = yoloname + modeltypes[(int)modelid] + tasknames[(int)taskid] + ".ncnn.param";
    std::string modelpath = yoloname + modeltypes[(int)modelid] + tasknames[(int)taskid] + ".ncnn.bin";
    bool use_gpu = (int)cpugpu == 1;
    bool use_turnip = (int)cpugpu == 2;

    // reload
    {
        ncnn::MutexLockGuard g(lock);

        {
            static int old_taskid = 0;
            static int old_modelid = 0;
            static int old_cpugpu = 0;
            if (taskid != old_taskid || (modelid % 3) != old_modelid || cpugpu != old_cpugpu)
            {
                // taskid or model or cpugpu changed
                delete g_yolo11;
                g_yolo11 = 0;
            }
            old_taskid = taskid;
            old_modelid = modelid % 3;
            old_cpugpu = cpugpu;

            ncnn::destroy_gpu_instance();

            if (use_turnip)
            {
                ncnn::create_gpu_instance("libvulkan_freedreno.so");
            }
            else if (use_gpu)
            {
                ncnn::create_gpu_instance();
            }

            if (!g_yolo11)
            {
                if (taskid == 0) g_yolo11 = new YOLO11_det;
                if (taskid == 1) g_yolo11 = new YOLO11_seg;
                if (taskid == 2) g_yolo11 = new YOLO11_pose;
                if (taskid == 3) g_yolo11 = new YOLO11_cls;
                if (taskid == 4) g_yolo11 = new YOLO11_obb;

                g_yolo11->load(mgr, parampath.c_str(), modelpath.c_str(), use_gpu || use_turnip);
            }
            int target_size = 320;
            if ((int)modelid >= 3)
                target_size = 480;
            if ((int)modelid >= 6)
                target_size = 640;
            g_yolo11->set_det_target_size(target_size);
        }
    }

    return JNI_TRUE;
}

// public native boolean openCamera(int facing);
JNIEXPORT jboolean JNICALL Java_com_tencent_yolo11ncnn_YOLO11Ncnn_openCamera(JNIEnv* env, jobject thiz, jint facing)
{
    if (facing < 0 || facing > 1)
        return JNI_FALSE;

    __android_log_print(ANDROID_LOG_DEBUG, "ncnn", "openCamera %d", facing);

    g_camera->open((int)facing);

    return JNI_TRUE;
}

// public native boolean closeCamera();
JNIEXPORT jboolean JNICALL Java_com_tencent_yolo11ncnn_YOLO11Ncnn_closeCamera(JNIEnv* env, jobject thiz)
{
    __android_log_print(ANDROID_LOG_DEBUG, "ncnn", "closeCamera");

    g_camera->close();

    return JNI_TRUE;
}

// public native boolean setOutputWindow(Surface surface);
JNIEXPORT jboolean JNICALL Java_com_tencent_yolo11ncnn_YOLO11Ncnn_setOutputWindow(JNIEnv* env, jobject thiz, jobject surface)
{
    ANativeWindow* win = ANativeWindow_fromSurface(env, surface);

    __android_log_print(ANDROID_LOG_DEBUG, "ncnn", "setOutputWindow %p", win);

    g_camera->set_window(win);

    return JNI_TRUE;
}

// public native void setDisplayOrientation(int degrees);
JNIEXPORT void JNICALL Java_com_tencent_yolo11ncnn_YOLO11Ncnn_setDisplayOrientation(JNIEnv* env, jobject thiz, jint degrees)
{
    int d = degrees % 360;
    if (d < 0) d += 360;
    if (d == 0 || d == 90 || d == 180 || d == 270)
    {
        g_display_rotation.store(d);
        __android_log_print(ANDROID_LOG_DEBUG, "ncnn", "setDisplayOrientation %d", d);
    }
}

JNIEXPORT void JNICALL Java_com_tencent_yolo11ncnn_YOLO11Ncnn_setYoloEnabled(JNIEnv* env, jobject thiz, jboolean enabled)
{
    g_yolo_enabled.store(enabled);
}

JNIEXPORT void JNICALL Java_com_tencent_yolo11ncnn_YOLO11Ncnn_setAprilTagEnabled(JNIEnv* env, jobject thiz, jboolean enabled)
{
    g_apriltag_enabled.store(enabled);
}

}
