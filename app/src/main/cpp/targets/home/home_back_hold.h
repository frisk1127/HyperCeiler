/* SPDX-License-Identifier: AGPL-3.0-or-later */
#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>

namespace home_back_feedback {
struct HoldState {
    uintptr_t owner=0;
    uint64_t revision=0,start=0,moved=0,latest=0;
    float start_x=0,start_y=0,anchor_x=0,anchor_y=0,x=0,y=0,density=1,width=0;
    float limit=0,peak=0,rearm_from=0;
    bool active=false,retracting=false;
    void reset(){active=false;}
    bool update(uintptr_t receiver,int action,int pointers,float px,float py,int64_t time,
                float scale,float screen_width,uint64_t config,float limit_dp) {
        if(pointers!=1||!std::isfinite(px)||!std::isfinite(py)||!std::isfinite(scale)
            ||scale<.5f||scale>8||!std::isfinite(screen_width)||screen_width<=0||time<=0
            ||!std::isfinite(limit_dp)||limit_dp<32||limit_dp>256
            ||action==3||action==5||action==6){reset();return false;}
        uint64_t stamp=uint64_t(time);
        if(action==0) {
            active=true;owner=receiver;revision=config;start=moved=latest=stamp;
            start_x=anchor_x=x=px;start_y=anchor_y=y=py;density=scale;width=screen_width;
            limit=limit_dp*scale;peak=rearm_from=0;retracting=false;
            return true;
        }
        if(!active||owner!=receiver||revision!=config||stamp<latest||stamp-start>10000
            ||(action!=1&&action!=2)){reset();return false;}
        bool was_full=geometry(false);
        latest=stamp;x=px;y=py;
        if(std::hypot(x-anchor_x,y-anchor_y)>6*density){moved=stamp;anchor_x=x;anchor_y=y;}
        float distance=inward();
        if(retracting) {
            rearm_from=std::min(rearm_from,distance);
            if(distance>=limit&&distance-rearm_from>=4*density) {
                retracting=false;peak=distance;moved=stamp;anchor_x=x;anchor_y=y;
            }
        } else {
            peak=std::max(peak,distance);
            if(peak>=limit&&peak-distance>=4*density){retracting=true;rearm_from=distance;}
        }
        if(!was_full&&geometry(false)){moved=stamp;anchor_x=x;anchor_y=y;}
        return true;
    }
    bool left()const{return start_x<width/2;}
    float inward()const{return left()?x-start_x:start_x-x;}
    bool geometry(bool armed)const {
        float dx=x-start_x,dy=y-start_y;
        return active&&!retracting&&inward()>=limit-(armed?4*density:0)&&std::abs(dy)<=std::abs(dx);
    }
    uint64_t deadline()const{return std::max(start+350,moved+300);}
    bool ready(uint64_t now)const{return geometry(false)&&now>=deadline()&&now-start<=10000;}
};
}
