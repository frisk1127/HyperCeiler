#include "targets/home/home_back_arrow.h"
#include <fstream>
#include <iostream>

int main(int argc,char**argv) {
    if(argc!=2)return 2;
    std::ifstream file(argv[1],std::ios::binary);
    std::vector<char> raw((std::istreambuf_iterator<char>(file)),{});
    std::vector<std::byte> bytes(raw.size());std::memcpy(bytes.data(),raw.data(),raw.size());
    auto target=home_back_arrow::resolve(bytes);
    if(!target){std::cerr<<"real arrow contracts not resolved\n";return 1;}
    auto image=home_layout::elf_targets::parse(bytes);
    if(std::abs(target->pull_limit_dp-88)>0.01f){std::cerr<<"wrong native pull limit\n";return 1;}
    auto edited=bytes;unsigned changed=0;
    auto bounds=image->bounds(target->bitmap);
    for(uint64_t pc=bounds->begin;pc<bounds->end;pc+=4) {
        uint32_t word=*image->word(pc);
        if((word&0xffc003ff)!=0xbd400020)continue;
        word+=1u<<10;
        std::memcpy(edited.data()+*image->file_offset(pc,4),&word,4);changed++;
    }
    if(changed!=1||home_back_arrow::resolve(edited)){std::cerr<<"mismatched view layout accepted\n";return 1;}
    edited=bytes;changed=0;
    for(uint64_t pc=bounds->begin;pc<bounds->end;pc+=4) {
        auto to=nhk::arm64::branch_target(pc,*image->word(pc),true);
        if(!to||*to!=target->imports[3])continue;
        uint32_t nop=0xd503201f;
        std::memcpy(edited.data()+*image->file_offset(pc,4),&nop,4);changed++;
    }
    if(changed!=1||home_back_arrow::resolve(edited)){std::cerr<<"missing bitmap drawing call accepted\n";return 1;}
    edited=bytes;edited[0]=std::byte{0};
    if(home_back_arrow::resolve(edited)){std::cerr<<"invalid ELF accepted\n";return 1;}
    edited=bytes;
    uint32_t nop=0xd503201f;
    std::memcpy(edited.data()+*image->file_offset(target->destroy+4,4),&nop,4);
    if(home_back_arrow::resolve(edited)){std::cerr<<"unverified view destructor accepted\n";return 1;}
    edited=bytes;bounds=image->bounds(target->arc_drop);changed=0;
    for(uint64_t pc=bounds->begin;pc<bounds->end;pc+=4) {
        auto to=nhk::arm64::branch_target(pc,*image->word(pc),true);
        if(to&&*to==target->destroy){std::memcpy(edited.data()+*image->file_offset(pc,4),&nop,4);changed++;}
    }
    if(changed!=1||home_back_arrow::resolve(edited)){std::cerr<<"unverified Arc lifetime accepted\n";return 1;}
    uint64_t comparison=0,conversion=0,constant=0;
    bounds=image->bounds(target->vsync);
    for(uint64_t pc=bounds->begin;pc+32<=bounds->end;pc+=4) {
        if(image->word(pc+8)!=0x1e269001||image->word(pc+16)!=0x1e211800)continue;
        auto to=nhk::arm64::branch_target(pc+4,*image->word(pc+4),true);
        if(to){comparison=pc+28;conversion=*to;}
    }
    if(!conversion||!comparison)return 1;
    bounds=image->bounds(conversion);
    for(uint64_t pc=bounds->begin;pc+16<=bounds->end;pc+=4)
        if(image->word(pc+4)==0x1e270100&&image->word(pc+12)==0x1e201901)constant=pc;
    if(!constant)return 1;
    auto replace=[&](uint64_t address,uint32_t word){std::memcpy(edited.data()+*image->file_offset(address,4),&word,4);};
    edited=bytes;replace(comparison,nop);
    if(home_back_arrow::resolve(edited)){std::cerr<<"missing pull threshold accepted\n";return 1;}
    edited=bytes;replace(constant,0x52a00008u|(0x7fc0u<<5));
    if(home_back_arrow::resolve(edited)){std::cerr<<"nonfinite pull limit accepted\n";return 1;}
    edited=bytes;replace(constant+8,nop);
    if(home_back_arrow::resolve(edited)){std::cerr<<"missing density conversion accepted\n";return 1;}
    edited=bytes;replace(constant,0x52a00008u|(0x42f0u<<5));
    auto alternate=home_back_arrow::resolve(edited);
    if(!alternate||std::abs(alternate->pull_limit_dp-96)>0.01f){std::cerr<<"changed native limit ignored\n";return 1;}
    std::cout<<"Arrow resolver: real image, alternate native limit and eight rejection cases passed\n";
}
