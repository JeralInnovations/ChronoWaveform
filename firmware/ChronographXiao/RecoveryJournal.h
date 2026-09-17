#pragma once
#include <Adafruit_LittleFS.h>
#include <InternalFileSystem.h>
#include <stddef.h>
#include <string.h>

// Alternate files: an interrupted write never intentionally replaces the newest
// verified copy. A complete write is closed and read back before becoming current.
template <typename Payload> class RecoveryJournal {
  struct Slot {
    uint32_t magic;
    uint32_t sequence;
    uint32_t payloadSize;
    Payload value;
    uint32_t checksum;
  };
  // Keep waveform-sized work buffers off the small embedded task stack.
  // Access is confined to setup()/loop(), never a BLE callback or ISR.
  Slot scratch = {};
  Slot verified = {};
  int current = -1;
  uint32_t sequence = 0;
  bool ready = false;
  const char* path(int slot) { return slot == 0 ? "/chrono_latest0.bin" : "/chrono_latest1.bin"; }
  uint32_t checksum(const Slot& s) {
    uint32_t crc = 0xffffffffUL;
    const uint8_t* data = (const uint8_t*)&s;
    for (size_t i = 0; i < offsetof(Slot, checksum); ++i) {
      crc ^= data[i];
      for (int b = 0; b < 8; ++b) crc = (crc >> 1) ^ (0xedb88320UL & (0UL - (crc & 1)));
    }
    return ~crc;
  }
  bool read(int index, Slot& s) {
    Adafruit_LittleFS_Namespace::File file(InternalFS);
    if (!file.open(path(index), Adafruit_LittleFS_Namespace::FILE_O_READ)) return false;
    bool ok = file.size() == sizeof(s) && file.read(&s, sizeof(s)) == (int)sizeof(s);
    file.close();
    return ok && s.magic == 0x43575231UL && s.payloadSize == sizeof(Payload) && s.checksum == checksum(s);
  }
public:
  bool begin(Payload& restored, bool& found) {
    found = false;
    ready = InternalFS.begin();
    if (!ready) return false;
    Slot& a = scratch;
    Slot& b = verified;
    bool av = read(0, a), bv = read(1, b);
    if (av || bv) {
      current = bv && (!av || (int32_t)(b.sequence - a.sequence) > 0) ? 1 : 0;
      const Slot& best = current == 0 ? a : b;
      sequence = best.sequence;
      restored = best.value;
      found = true;
    }
    return true;
  }
  bool save(const Payload& value) {
    if (!ready) return false;
    int target = current == 0 ? 1 : 0;
    Slot& next = scratch;
    memset(&next, 0, sizeof(next));
    next.magic = 0x43575231UL;
    next.sequence = sequence + 1;
    next.payloadSize = sizeof(Payload);
    next.value = value;
    next.checksum = checksum(next);
    InternalFS.remove(path(target));
    Adafruit_LittleFS_Namespace::File file(InternalFS);
    if (!file.open(path(target), Adafruit_LittleFS_Namespace::FILE_O_WRITE)) return false;
    bool written = file.write((const uint8_t*)&next, sizeof(next)) == sizeof(next);
    file.flush();
    file.close();
    if (!written || !read(target, verified) || memcmp(&next, &verified, sizeof(next))) return false;
    current = target;
    sequence = next.sequence;
    return true;
  }
};
