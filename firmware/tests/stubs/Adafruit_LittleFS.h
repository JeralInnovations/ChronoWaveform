#pragma once
#include <stdint.h>
#include <string>
#include <vector>
#include <map>
#include <algorithm>
#include <cstring>
struct FakeFS {
  std::map<std::string, std::vector<uint8_t>> files;
  int failAfter = -1;
  bool begin() { return true; }
  bool remove(const char* path) { return files.erase(path); }
};
namespace Adafruit_LittleFS_Namespace {
enum { FILE_O_READ, FILE_O_WRITE };
class File {
  FakeFS& fs; std::string path;
public:
  File(FakeFS& fs): fs(fs) {}
  bool open(const char* p, int mode) { path=p; if (mode==FILE_O_WRITE) fs.files[path]={}; return fs.files.count(path); }
  size_t size() { return fs.files[path].size(); }
  int read(void* p, uint16_t n) { auto& v=fs.files[path]; size_t count=std::min<size_t>(n,v.size()); memcpy(p,v.data(),count); return count; }
  size_t write(const uint8_t* p,size_t n) { size_t count=fs.failAfter<0?n:std::min<size_t>(n,fs.failAfter); fs.files[path].assign(p,p+count); return count; }
  void flush() {}
  void close() {}
};
}
