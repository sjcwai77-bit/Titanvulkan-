#include <game-activity/native_app_glue/android_native_app_glue.h>
#include <game-activity/GameActivity.h>
#include <android/input.h>
#include <android/log.h>
#include <android/native_window.h>
#include <atomic>
#include <chrono>
#include <cmath>
#include <jni.h>

#include "math.hpp"
#include "titanic_scene.hpp"
#include "vulkan_renderer.hpp"

using namespace tv;
namespace {
constexpr const char* TAG="TitanicVulkan";
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,TAG,__VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR,TAG,__VA_ARGS__)

class Engine;
Engine* gEngine=nullptr;

class Engine {
public:
    explicit Engine(android_app* app):app_(app){
        gEngine=this;
        app_->userData=this;
        app_->onAppCmd=&Engine::onAppCmdThunk;
        android_app_set_key_event_filter(app_,nullptr);
        android_app_set_motion_event_filter(app_,nullptr);
        applyPreset(0,true);
        lastFrame_=std::chrono::steady_clock::now();
    }
    ~Engine(){renderer_.shutdown(); if(gEngine==this)gEngine=nullptr;}

    void setPaused(bool p){paused_.store(p);}
    void setSpeed(float s){simSpeed_.store(clampf(s,.1f,20.0f));}
    void setCameraPreset(int p){presetRequest_.store(p);}
    void resetCamera(){resetCameraRequested_.store(true);}

    void run(){
        while(!app_->destroyRequested){
            int events=0;
            android_poll_source* source=nullptr;
            const int timeout=renderer_.ready()?0:-1;
            const int ident=ALooper_pollOnce(timeout,nullptr,&events,reinterpret_cast<void**>(&source));
            if(ident>=0 && source)source->process(app_,source);
            if(app_->destroyRequested)return;
            processInput();
            processUiRequests();
            if(renderer_.ready())frame();
        }
    }

private:
    android_app* app_{};
    TitanicScene scene_{};
    VulkanRenderer renderer_{};
    std::atomic<bool> paused_{false};
    std::atomic<float> simSpeed_{1.0f};
    std::atomic<int> presetRequest_{-1};
    std::atomic<bool> resetCameraRequested_{false};
    float presentationTime_=0.0f;
    std::chrono::steady_clock::time_point lastFrame_{};

    float yaw_=3.83f,pitch_=.24f,distance_=62.0f;
    float targetYaw_=3.83f,targetPitch_=.24f,targetDistance_=62.0f;
    float yawVel_=0.0f,pitchVel_=0.0f;
    Vec3 target_{0,5.8f,0},targetGoal_{0,5.8f,0};

    bool touchDown_=false;
    float lastX_=0,lastY_=0,lastPinch_=0,lastMidX_=0,lastMidY_=0;

    static void onAppCmdThunk(android_app* app,int32_t cmd){static_cast<Engine*>(app->userData)->onAppCmd(cmd);}
    void onAppCmd(int32_t cmd){
        switch(cmd){
            case APP_CMD_INIT_WINDOW:
                if(app_->window){
                    renderer_.shutdown();
                    if(!renderer_.init(app_->window,app_->activity->assetManager,scene_.meshes()))LOGE("Renderer init failed");
                    else LOGI("Vulkan surface %dx%d",ANativeWindow_getWidth(app_->window),ANativeWindow_getHeight(app_->window));
                    lastFrame_=std::chrono::steady_clock::now();
                }
                break;
            case APP_CMD_TERM_WINDOW: renderer_.shutdown(); break;
            case APP_CMD_START:
            case APP_CMD_RESUME:
            case APP_CMD_GAINED_FOCUS: lastFrame_=std::chrono::steady_clock::now(); break;
            default: break;
        }
    }

    void applyPreset(int p,bool immediate=false){
        p=((p%4)+4)%4;
        if(p==0){targetYaw_=3.83f;targetPitch_=.25f;targetDistance_=61.0f;targetGoal_={0,5.8f,0};}
        if(p==1){targetYaw_=4.25f;targetPitch_=.17f;targetDistance_=68.0f;targetGoal_={0,5.2f,0};}
        if(p==2){targetYaw_=2.68f;targetPitch_=.19f;targetDistance_=72.0f;targetGoal_={7,5.4f,0};}
        if(p==3){targetYaw_=5.82f;targetPitch_=.20f;targetDistance_=69.0f;targetGoal_={-7,5.0f,0};}
        yawVel_=pitchVel_=0;
        if(immediate){yaw_=targetYaw_;pitch_=targetPitch_;distance_=targetDistance_;target_=targetGoal_;}
    }

    void processUiRequests(){
        int p=presetRequest_.exchange(-1);if(p>=0)applyPreset(p,false);
        if(resetCameraRequested_.exchange(false))applyPreset(0,false);
    }

    void processInput(){
        android_input_buffer* ib=android_app_swap_input_buffers(app_);if(!ib)return;
        for(uint64_t i=0;i<ib->motionEventsCount;i++){
            GameActivityMotionEvent& e=ib->motionEvents[i];
            const int action=e.action&AMOTION_EVENT_ACTION_MASK;
            if(e.pointerCount==0)continue;
            auto x=[&](uint32_t p){return GameActivityPointerAxes_getAxisValue(&e.pointers[p],AMOTION_EVENT_AXIS_X);};
            auto y=[&](uint32_t p){return GameActivityPointerAxes_getAxisValue(&e.pointers[p],AMOTION_EVENT_AXIS_Y);};
            if(action==AMOTION_EVENT_ACTION_DOWN){
                touchDown_=true;lastX_=x(0);lastY_=y(0);lastPinch_=0;
            }else if(action==AMOTION_EVENT_ACTION_POINTER_DOWN && e.pointerCount>=2){
                float dx=x(1)-x(0),dy=y(1)-y(0);lastPinch_=std::sqrt(dx*dx+dy*dy);lastMidX_=(x(0)+x(1))*.5f;lastMidY_=(y(0)+y(1))*.5f;
            }else if(action==AMOTION_EVENT_ACTION_MOVE){
                if(e.pointerCount>=2){
                    float dx=x(1)-x(0),dy=y(1)-y(0),d=std::sqrt(dx*dx+dy*dy);
                    float mx=(x(0)+x(1))*.5f,my=(y(0)+y(1))*.5f;
                    if(lastPinch_>10&&d>10)targetDistance_=clampf(targetDistance_*(lastPinch_/d),24.0f,145.0f);
                    float panScale=targetDistance_*.0017f;
                    Vec3 right={-std::sin(yaw_),0,std::cos(yaw_)};
                    Vec3 up={0,1,0};
                    targetGoal_=targetGoal_ + right*((lastMidX_-mx)*panScale) + up*((my-lastMidY_)*panScale);
                    targetGoal_.y=clampf(targetGoal_.y,-8.0f,18.0f);
                    lastPinch_=d;lastMidX_=mx;lastMidY_=my;
                }else if(touchDown_){
                    float nx=x(0),ny=y(0),dx=nx-lastX_,dy=ny-lastY_;
                    targetYaw_-=dx*.0042f;targetPitch_-=dy*.0035f;targetPitch_=clampf(targetPitch_,-.02f,1.10f);
                    yawVel_=-dx*.00085f;pitchVel_=-dy*.00070f;
                    lastX_=nx;lastY_=ny;
                }
            }else if(action==AMOTION_EVENT_ACTION_UP||action==AMOTION_EVENT_ACTION_CANCEL){touchDown_=false;lastPinch_=0;}
        }
        android_app_clear_motion_events(ib);android_app_clear_key_events(ib);
    }

    void updateCamera(float dt){
        if(!touchDown_){targetYaw_+=yawVel_*dt*60.0f;targetPitch_=clampf(targetPitch_+pitchVel_*dt*60.0f,-.02f,1.10f);yawVel_*=std::pow(.90f,dt*60.0f);pitchVel_*=std::pow(.88f,dt*60.0f);}
        float s=1.0f-std::exp(-dt*7.0f);
        yaw_=lerpf(yaw_,targetYaw_,s);pitch_=lerpf(pitch_,targetPitch_,s);distance_=lerpf(distance_,targetDistance_,s);
        target_.x=lerpf(target_.x,targetGoal_.x,s);target_.y=lerpf(target_.y,targetGoal_.y,s);target_.z=lerpf(target_.z,targetGoal_.z,s);
    }

    void frame(){
        auto now=std::chrono::steady_clock::now();float dt=std::chrono::duration<float>(now-lastFrame_).count();lastFrame_=now;dt=clampf(dt,0.0f,.05f);
        if(!paused_.load()&&!scene_.finished()){presentationTime_=std::min(300.0f,presentationTime_+dt*simSpeed_.load());scene_.update(presentationTime_);}
        updateCamera(dt);

        float hm=scene_.historicalMinutes();float desiredY=(hm>157)?lerpf(5.8f,-4.0f,smoothstep(157.0f,160.0f,hm)):5.8f;
        if(!touchDown_ && hm>157)targetGoal_.y=lerpf(targetGoal_.y,desiredY,.015f);

        float cp=std::cos(pitch_),sp=std::sin(pitch_),cy=std::cos(yaw_),sy=std::sin(yaw_);
        Vec3 cam={target_.x+distance_*cp*cy,target_.y+distance_*sp,target_.z+distance_*cp*sy};
        int w=std::max(1,ANativeWindow_getWidth(app_->window)),h=std::max(1,ANativeWindow_getHeight(app_->window));
        Mat4 view=Mat4::lookAt(cam,target_,{0,1,0});
        Mat4 proj=Mat4::perspective(radians(42.0f),float(w)/float(h),.08f,1200.0f);
        renderer_.draw(scene_.meshes(),proj*view,cam,presentationTime_);
    }
};
}

extern "C" JNIEXPORT void JNICALL Java_com_sjcw_titanicvulkan_MainActivity_nativeSetPaused(JNIEnv*,jclass,jboolean p){if(gEngine)gEngine->setPaused(p);}
extern "C" JNIEXPORT void JNICALL Java_com_sjcw_titanicvulkan_MainActivity_nativeSetSpeed(JNIEnv*,jclass,jfloat s){if(gEngine)gEngine->setSpeed(s);}
extern "C" JNIEXPORT void JNICALL Java_com_sjcw_titanicvulkan_MainActivity_nativeSetCameraPreset(JNIEnv*,jclass,jint p){if(gEngine)gEngine->setCameraPreset(p);}
extern "C" JNIEXPORT void JNICALL Java_com_sjcw_titanicvulkan_MainActivity_nativeResetCamera(JNIEnv*,jclass){if(gEngine)gEngine->resetCamera();}

extern "C" void android_main(struct android_app* app){Engine engine(app);engine.run();}
