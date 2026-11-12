/* SPDX-License-Identifier: AGPL-3.0-or-later */
#pragma once
#include "home_layout_elf_targets.h"
#include <cmath>

namespace home_back_arrow {
inline constexpr std::array<const char*,11> names={"RustBitmap_create","RustBitmap_copyPixelsFromBuffer",
    "RustBitmap_destruct","RustCanvas_draw_bitmap_rect","RustPaint_init","RustPaint_set_alpha",
    "RustPaint_drop","RustPaint_set_filter_bitmap","RustCanvas_draw_path","NativeWindow_dec_strong",
    "Configuration_get_density_dpi"};
struct Targets {
    uint64_t path=0,bitmap=0,positions=0,vsync=0,destroy=0,arc_drop=0;
    uint32_t height=0,right=0,rect=0,arc_offset=0;
    float pull_limit_dp=0;
    std::array<uint64_t,names.size()> imports{};
};
inline std::optional<float> pull_limit(const home_layout::elf_targets::Image& image,
                                      uint64_t frame,uint64_t density_api) {
    auto bounds=image.bounds(frame);if(!bounds)return {};
    auto scalar=[&](uint64_t pc,uint32_t page_word,uint32_t load)->std::optional<float> {
        auto page=nhk::arm64::decode_adrp(pc,page_word);if(!page||(page_word&31)!=8)return {};
        auto bits=image.word(*page+((load>>10)&4095)*4);if(!bits)return {};
        float value;std::memcpy(&value,&*bits,4);
        return std::isfinite(value)?std::optional<float>(value):std::nullopt;
    };
    std::optional<float> result;
    for(uint64_t pc=bounds->begin;pc+32<=bounds->end;pc+=4) {
        std::array<uint32_t,8> w{};
        for(unsigned i=0;i<w.size();i++){auto v=image.word(pc+4*i);if(!v)return {};w[i]=*v;}
        if((w[0]&0xffc003ff)!=0xbd400260||w[2]!=0x1e269001||w[4]!=0x1e211800
            ||(w[5]&0xffc003ff)!=0xbd400101||w[6]!=0x1e212000||(w[7]&0xff00001f)!=0x5400000b)continue;
        auto convert=nhk::arm64::branch_target(pc+4,w[1],true);
        auto threshold=scalar(pc+12,w[3],w[5]);
        if(!convert||!threshold||*threshold<.5f||*threshold>1)return {};
        auto function=image.bounds(*convert);
        if(!function||function->begin!=*convert||function->end-*convert>256)return {};
        std::optional<float> distance;
        for(uint64_t at=function->begin;at+32<=function->end;at+=4) {
            std::array<uint32_t,8> v{};
            for(unsigned i=0;i<v.size();i++){auto word=image.word(at+4*i);if(!word)return {};v[i]=*word;}
            if((v[0]&0xffe0001f)!=0x52a00008||v[1]!=0x1e270100||v[3]!=0x1e201901
                ||(v[5]&0xffc003ff)!=0xbd400100||v[6]!=0x1e202020||(v[7]&0xff00001f)!=0x54000004)continue;
            auto bend=scalar(at+16,v[4],v[5]);
            auto density=nhk::arm64::branch_target(at+8,v[2],true);
            if(!bend||*bend!=*threshold||!density)return {};
            auto dp=image.bounds(*density);
            if(!dp||dp->begin!=*density||dp->end-*density>256)return {};
            bool dpi=false,scale=false;
            for(uint64_t a=dp->begin;a+16<=dp->end;a+=4) {
                auto word=image.word(a);if(!word)return {};
                auto to=nhk::arm64::branch_target(a,*word,true);dpi|=to&&*to==density_api;
                scale|=*word==0x52a86408&&image.word(a+4)==0x1e200820
                    &&image.word(a+8)==0x1e270101&&image.word(a+12)==0x1e211808;
            }
            if(!dpi||!scale||distance)return {};
            uint32_t bits=((v[0]>>5)&65535)<<16;float dp_value;std::memcpy(&dp_value,&bits,4);
            float limit=dp_value * (*threshold);
            if(!std::isfinite(limit)||limit<32||limit>256)return {};
            distance=limit;
        }
        if(!distance||result)return {};result=distance;
    }
    return result;
}
inline std::optional<Targets> resolve(std::span<const std::byte> bytes) {
    using namespace home_layout::elf_targets;
    auto image=parse(bytes);if(!image)return {};
    uint64_t size=0;
    for(const auto&s:image->segments)if(s.type==1) {
        if(s.vaddr>128*1024*1024||s.memsz>128*1024*1024-s.vaddr)return {};
        size=std::max(size,s.vaddr+s.memsz);
    }
    std::vector<std::byte> snapshot(size);
    for(const auto&s:image->segments)if(s.type==1) {
        if(s.filesz>s.memsz||!nhk::in_image(bytes.size(),s.offset,s.filesz))return {};
        std::memcpy(snapshot.data()+s.vaddr,bytes.data()+s.offset,s.filesz);
    }
    auto dynamic=nhk::elf::ElfImage::make(0,snapshot);if(!dynamic)return {};
    std::array<uint64_t,names.size()> slots{};Targets result;
    for(size_t i=0;i<names.size();i++) {
        auto slot=dynamic->unique_slot(names[i]);if(!slot)return {};slots[i]=slot->rva;
    }
    for(const auto&s:image->segments)if(s.type==1&&(s.flags&1))
        for(uint64_t pc=s.vaddr;pc+16<=s.vaddr+s.filesz;pc+=4) {
            std::array<uint32_t,4> words{};
            for(unsigned i=0;i<4;i++)words[i]=*image->word(pc+4*i);
            auto got=nhk::arm64::decode_plt_got(words,0,pc);if(!got)continue;
            for(size_t i=0;i<slots.size();i++)if(*got==slots[i]) {
                if(result.imports[i])return {};result.imports[i]=pc;
            }
        }
    for(auto address:result.imports)if(!address)return {};
    auto eh=image->unwind_header;
    if(eh.size()<12||eh[0]!=std::byte{1}||eh[3]!=std::byte{0x3b})return {};
    uint32_t count=nhk::load_le32(eh.data()+8);
    if(count<2||count>(eh.size()-12)/8)return {};
    uint32_t path_height=0,bitmap_height=0;
    for(uint32_t i=0;i+1<count;i++) {
        uint64_t begin=uint64_t(int64_t(image->unwind_vaddr)+int32_t(nhk::load_le32(eh.data()+12+i*8)));
        uint64_t end=uint64_t(int64_t(image->unwind_vaddr)+int32_t(nhk::load_le32(eh.data()+20+i*8)));
        if(end<=begin||end-begin>768||!image->file_offset(begin,end-begin))continue;
        auto contains=[&](uint32_t wanted) {
            for(uint64_t pc=begin;pc<end;pc+=4)if(image->word(pc)==wanted)return true;
            return false;
        };
        auto calls=[&](uint64_t address) {
            unsigned n=0;
            for(uint64_t pc=begin;pc<end;pc+=4) {
                auto w=image->word(pc);if(!w)continue;
                auto target=nhk::arm64::branch_target(pc,*w,true);n+=target&&*target==address;
            }
            return n;
        };
        if(contains(0x2d410460)&&contains(0x2d412aab)&&contains(0xb94012a1)
            &&contains(0xaa0203f4)&&contains(0xaa0403f5)&&calls(result.imports[8])==1&&calls(result.imports[5])==1) {
            if(result.path)return {};result.path=begin;
            for(uint64_t pc=begin;pc<end;pc+=4) {
                auto w=*image->word(pc);
                if((w&0xffc003ff)==0xbd4002c1)path_height=((w>>10)&4095)*4;
            }
        }
        if(contains(0x2d410482)&&contains(0x2d412c6a)&&contains(0xb9401081)
            &&contains(0xaa0203f4)&&calls(result.imports[3])==1&&calls(result.imports[5])==1) {
            if(result.bitmap)return {};result.bitmap=begin;
            for(uint64_t pc=begin;pc<end;pc+=4) {
                auto w=*image->word(pc);
                if((w&0xffc003ff)==0xbd400020)bitmap_height=((w>>10)&4095)*4;
            }
        }
        if(contains(0xbd400042)&&contains(0xbd400c43)&&contains(0x1e220808)&&contains(0x1e230829)) {
            uint32_t side=0,rect=0;
            for(uint64_t pc=begin;pc<end;pc+=4) {
                auto w=*image->word(pc);
                if((w&0xffc003ff)==0x39400028)side=(w>>10)&4095;
                auto callee=nhk::arm64::branch_target(pc,w,true);if(!callee)continue;
                auto bounds=image->bounds(*callee);if(!bounds||bounds->begin!=*callee||bounds->end-*callee>160
                    ||!image->file_offset(*callee,bounds->end-*callee))continue;
                for(uint64_t a=*callee;a<bounds->end;a+=4) {
                    auto op=*image->word(a);
                    if((op&0xffc003ff)==0x91000029&&image->word(a+4)==0x3dc00120)rect=(op>>10)&4095;
                }
            }
            if(side&&rect) {
                if(result.right)return {};result.right=side;result.rect=rect;result.positions=begin;
            }
        }
    }
    if(!result.path||!result.bitmap||!path_height||path_height!=bitmap_height
        ||result.right<=path_height||result.right>path_height+16||!result.rect||result.rect+16>path_height)return {};
    result.height=path_height;
    for(uint32_t i=0;i+1<count;i++) {
        uint64_t begin=uint64_t(int64_t(image->unwind_vaddr)+int32_t(nhk::load_le32(eh.data()+12+i*8)));
        uint64_t end=uint64_t(int64_t(image->unwind_vaddr)+int32_t(nhk::load_le32(eh.data()+20+i*8)));
        if(end<=begin||end-begin>16384||!image->file_offset(begin,end-begin))continue;
        uint32_t first=*image->word(begin);
        bool drop=(first&0xffc003ff)==0x39400008&&((first>>10)&4095)==result.right
            &&image->word(begin+4)==0x7100091f&&image->word(begin+12)==0xd65f03c0&&end-begin<768;
        bool frame=first==0x6db63bef&&end-begin>1024;
        if(!drop&&!frame)continue;
        unsigned paint=0,window=0,positions=0;bool receiver=false,hidden=false;
        for(uint64_t pc=begin;pc<end;pc+=4) {
            uint32_t w=*image->word(pc);
            if(pc-begin<80) {
                receiver|=w==0xaa0003f3;
                hidden|=(w&0xffc003ff)==0x39400008&&((w>>10)&4095)==result.height+4;
            }
            auto target=nhk::arm64::branch_target(pc,w,true);if(!target)continue;
            paint+=*target==result.imports[6];window+=*target==result.imports[9];positions+=*target==result.positions;
        }
        if(drop&&paint==3&&window==1) {if(result.destroy)return {};result.destroy=begin;}
        if(frame&&receiver&&hidden&&positions==2) {if(result.vsync)return {};result.vsync=begin;}
    }
    if(!result.vsync||!result.destroy)return {};
    auto limit=pull_limit(*image,result.vsync,result.imports[10]);if(!limit)return {};
    result.pull_limit_dp=*limit;
    for(uint32_t i=0;i+1<count;i++) {
        uint64_t begin=uint64_t(int64_t(image->unwind_vaddr)+int32_t(nhk::load_le32(eh.data()+12+i*8)));
        uint64_t end=uint64_t(int64_t(image->unwind_vaddr)+int32_t(nhk::load_le32(eh.data()+20+i*8)));
        if(end<=begin||end-begin>128||!image->file_offset(begin,end-begin)||image->word(begin+12)!=0xaa0003f3)continue;
        bool weak=false;uint32_t offset=0;
        for(uint64_t pc=begin;pc<end;pc+=4) {
            uint32_t w=*image->word(pc);weak|=w==0x91002268;
            auto to=nhk::arm64::branch_target(pc,w,true);
            if(to&&*to==result.destroy&&pc>=begin+4) {
                uint32_t add=*image->word(pc-4);
                if((add&0xffc003ff)==0x91000000)offset=(add>>10)&4095;
            }
        }
        if(weak&&offset>=24&&offset<=64&&(offset%8)==0) {
            if(result.arc_drop)return {};result.arc_drop=begin;result.arc_offset=offset;
        }
    }
    return result.arc_drop?std::optional<Targets>(result):std::nullopt;
}
}

extern void* hc_back_path_draw_original;
extern void* hc_back_bitmap_draw_original;
extern void* hc_back_view_vsync_original;
extern void* hc_back_view_destroy_original;
extern "C" void hc_back_path_draw(void*,void*,void*,const float*,const float*);
extern "C" void hc_back_bitmap_draw(void*,void*,void*,const float*,const float*);
extern "C" void hc_back_view_vsync(void*);
extern "C" void hc_back_view_destroy(void*);
void hc_back_arrow_bind(uintptr_t,const home_back_arrow::Targets&);
void hc_back_arrow_refresh();
