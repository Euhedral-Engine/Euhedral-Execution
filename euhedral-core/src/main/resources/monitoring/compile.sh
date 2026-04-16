#!/bin/bash -xe
rm -rf ./bin
mkdir bin

zig c++ -target aarch64-windows-gnu -shared -O3 -s -o ./bin/windows_resources_arm64.dll \
  -fno-exceptions -fno-rtti \
  -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/win32" \
  windows_resources.cpp -lpsapi -lkernel32
rm -f ./bin/windows_resources_arm64.lib ./bin/windows_resources_arm64.pdb

zig c++ -target x86_64-windows-gnu -shared -O3 -s -o ./bin/windows_resources_x64.dll \
  -fno-exceptions -fno-rtti \
  -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/win32" \
  windows_resources.cpp -lpsapi -lkernel32
rm -f ./bin/windows_resources.lib ./bin/windows_resources.pdb ./bin/windows_resources_x64.lib ./bin/windows_resources_x64.pdb

zig c++ -target x86_64-macos.10.9 -shared -O3 -s -o ./bin/mac_resources_x64.dylib \
  -fno-exceptions -fno-rtti \
  -iframework "$MACOS_SDK/Frameworks" \
  -F "$MACOS_SDK/Frameworks" \
  -L "$MACOS_SDK/lib" \
  -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/darwin" \
  mac_resources.cpp -framework CoreFoundation

zig c++ -target aarch64-macos.11.0 -shared -O3 -s -o ./bin/mac_resources_arm64.dylib \
  -fno-exceptions -fno-rtti \
  -iframework "$MACOS_SDK/Frameworks" \
  -F "$MACOS_SDK/Frameworks" \
  -L "$MACOS_SDK/lib" \
  -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/darwin" \
  mac_resources.cpp -framework CoreFoundation
