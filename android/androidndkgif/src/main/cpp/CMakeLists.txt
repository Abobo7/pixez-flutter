cmake_minimum_required(VERSION 3.22.1)
project("androidndkgif")

set(CMAKE_CXX_STANDARD 11)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(androidndkgif SHARED
   DataBlock.cpp
   BitmapIterator.cpp
   BitWritingBlock.cpp
   GifDecoder.cpp
   GifEncoder.cpp
   GifFrame.cpp
   com_waynejo_androidndkgif_GifDecoder.cpp
   com_waynejo_androidndkgif_GifEncoder.cpp
   GCTGifEncoder.cpp
   LCTGifEncoder.cpp
   FastGifEncoder.cpp
   SimpleGCTEncoder.cpp
   BaseGifEncoder.cpp
)

add_library(jnicompat19 SHARED
   kitkat_bionic_compat.c
)

target_compile_definitions(androidndkgif PRIVATE
   __GXX_EXPERIMENTAL_CXX0X__
)

if(ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "x86_64")
   target_compile_definitions(androidndkgif PRIVATE __ANDROID_USE_LARGE_PAGES__)
   target_link_options(androidndkgif PRIVATE
      "LINKER:--hash-style=both"
      "LINKER:-z,max-page-size=16384"
      "LINKER:-z,common-page-size=16384"
   )
   target_link_options(jnicompat19 PRIVATE
      "LINKER:--hash-style=both"
      "LINKER:-z,max-page-size=16384"
      "LINKER:-z,common-page-size=16384"
   )
else()
   target_link_options(androidndkgif PRIVATE
      "LINKER:--hash-style=both"
   )
   target_link_options(jnicompat19 PRIVATE
      "LINKER:--hash-style=both"
   )
endif()

target_link_libraries(androidndkgif
   jnigraphics
   log
)

target_link_libraries(jnicompat19
   dl
   log
   m
   c
)
