#include <cassert>
#include <iostream>
#include "../ChronographXiao/RecoveryJournal.h"
struct Capture { uint32_t boot, id; uint8_t waveform[1024]; };
int main() {
  RecoveryJournal<Capture> journal;
  Capture restored={}, first={17,4,{1,2,3}}, second={18,5,{7,8,9}};
  bool found=false;
  assert(journal.begin(restored,found) && !found);
  assert(journal.save(first));
  // Power loss after a partial next write must leave the previous capture usable.
  InternalFS.failAfter=100;
  assert(!journal.save(second));
  RecoveryJournal<Capture> reboot;
  assert(reboot.begin(restored,found) && found && restored.boot==17 && restored.id==4);
  assert(restored.waveform[2]==3);
  InternalFS.failAfter=-1;
  assert(reboot.save(second));
  RecoveryJournal<Capture> rebootAgain;
  assert(rebootAgain.begin(restored,found) && found && restored.id==5 && restored.waveform[2]==9);
  // Corruption of the newest full-size slot falls back to the older verified slot.
  InternalFS.files["/chrono_latest1.bin"][20]^=0x80;
  RecoveryJournal<Capture> corrupt;
  assert(corrupt.begin(restored,found) && found && restored.id==4);
  InternalFS.files["/chrono_latest0.bin"].clear();
  RecoveryJournal<Capture> bothInvalid;
  assert(bothInvalid.begin(restored,found) && !found);
  std::cout << "Recovery journal: empty, complete, interrupted, reboot, corrupt fallback checks passed\n";
}
