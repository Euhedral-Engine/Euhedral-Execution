#!/bin/bash
rm -rf ./bin && mkdir bin bin/windows bin/mac bin/linux
shopt -s globstar

FILE_EXT="cpp"
COMMON_FLAGS="-shared -O3 -fno-stack-check -fno-exceptions -fno-rtti -s"

compileWindows () {
  zig c++ -target $1-windows-gnu $COMMON_FLAGS -flto \
            -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/win32" \
            -o "./bin/windows/$3_$4.dll" \
            "$2" -lpsapi -lkernel32
}

compileOSX () {
  zig c++ -target "$1" $COMMON_FLAGS \
          -iframework "$MACOS_SDK/System/Library/Frameworks" \
          -F "$MACOS_SDK/System/Library/Frameworks" \
          -L "$MACOS_SDK/usr/lib" \
          -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/darwin" \
          -framework CoreFoundation \
          -o "./bin/mac/$3_$4.dylib" \
          "$2"
}

compileLinux () {
  zig c++ -target "$1-linux" $COMMON_FLAGS -fPIC -flto \
          -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
          -o "./bin/linux/$3_$4.so" \
          "$2"
}

for dir in "./linux" "./osx" "./windows"; do
  for file in "$dir"/*."$FILE_EXT"; do
    [ -e "$file" ] || continue

    name=$(basename "${file%.*}")

    arch_name=("x86_64:x64" "aarch64:arm64")
    if [ "$dir" = "./osx" ]; then
      arch_name=("x86_64-macos.10.9:x64" "aarch64-macos.11.0:arm64")
    fi

    for arch in "${arch_name[@]}"; do
      IFS=":" read -r target suffix <<< "$arch"

      echo "Compiling $file for $target..."

      case "$dir" in
        "./linux")   compileLinux "$target" "$file" "$name" "$suffix" ;;
        "./osx")   compileOSX "$target" "$file" "$name" "$suffix" ;;
        "./windows") compileWindows "$target" "$file" "$name" "$suffix" ;;
      esac
    done
  done
done

rm -f ./bin/**/*.lib ./bin/**/*.pdb
