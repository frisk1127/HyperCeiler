#include "targets/home/home_back_hold.h"
#include <iostream>
#include <limits>
#include <stdexcept>

int main() {
    using home_back_feedback::HoldState;
    HoldState state;unsigned checks=0;
    auto check=[&](bool value,const char* message){++checks;if(!value)throw std::runtime_error(message);};
    auto event=[&](int action,float x,float y,int64_t time){return state.update(1,action,1,x,y,time,3,1200,7,88);};
    event(0,4,1000,1000);event(2,180,1000,1150);
    check(!state.ready(2000),"partial pull must not arm despite a long hold");
    event(2,267,1000,2100);check(!state.ready(2500),"below native limit");
    event(2,268,1000,2600);
    check(!state.ready(2899),"early full pull");check(state.ready(2900),"native limit and hold");
    event(2,265,1004,2910);check(state.geometry(true),"small jitter while armed");
    event(2,256,1000,2920);check(!state.geometry(true),"four dp withdrawal cancels");
    check(!state.ready(4000),"withdrawal must not rearm while stationary");
    event(2,268,1000,4100);check(!state.ready(4399),"repull starts a fresh hold");
    check(state.ready(4400),"repull to full limit rearms");

    event(0,4,1000,5000);event(2,454,1000,5200);
    check(state.ready(5500),"overpull arms");
    for(int n=1;n<=4;n++)event(2,454-3*n,1000,5500+n*10);
    check(!state.geometry(true),"small withdrawals accumulate from peak");
    check(!state.ready(6000),"overpull withdrawal stays cancelled above limit");
    event(2,400,1000,6100);event(2,412,1000,6200);
    check(state.ready(6500),"new inward pull after withdrawal rearms");

    event(0,1196,1000,7000);event(2,932,1100,7200);
    check(state.ready(7500)&&!state.left(),"right full pull");
    event(2,944,1100,7510);check(!state.geometry(true),"right withdrawal");
    event(0,1196,1000,8000);event(2,932,1300,8200);
    check(!state.ready(8500),"vertical movement");
    event(0,4,1000,9000);event(2,340,1000,9250);event(1,340,1000,9270);
    check(!state.ready(9270),"fast return");
    event(0,4,1000,10000);event(2,340,1000,10010);
    check(!state.ready(10349),"minimum duration");check(state.ready(10350),"minimum duration reached");

    event(0,4,1000,11000);event(2,340,1000,11200);
    check(!state.update(1,2,2,340,1000,11300,3,1200,7,88),"multi touch");
    check(!state.ready(11600),"cancel remains cancelled");
    event(0,4,1000,12000);check(!state.update(2,2,1,340,1000,12300,3,1200,7,88),"owner changed");
    event(0,4,1000,13000);check(!state.update(1,2,1,340,1000,13300,3,1200,8,88),"configuration changed");
    event(0,4,1000,14000);check(!event(3,300,1000,14400),"cancel action");
    event(0,4,1000,15000);check(!event(2,std::numeric_limits<float>::quiet_NaN(),1000,15100),"invalid position");
    event(0,4,1000,16000);check(!event(2,300,1000,15999),"time went backwards");
    event(0,4,1000,17000);check(!event(2,300,1000,27001),"stale gesture");
    check(!state.update(1,0,1,4,1000,28000,3,1200,7,0),"missing native limit");
    check(state.update(1,0,1,4,1000,29000,2,1200,7,100),"alternate native limit");
    check(state.update(1,2,1,203,1000,29200,2,1200,7,100)&&!state.ready(29500),"alternate limit respected");
    check(state.update(1,2,1,204,1000,29600,2,1200,7,100)&&state.ready(30000),"alternate limit reached");
    std::cout<<"Native hold: "<<checks<<" checks passed\n";
}
