# Illustration only: the three logDetailCat fixes with one sink added after the fact:
#   sink LOG_READ *ExecutorBiz#log

## 7a5239f3
# Review manifest

2 methods changed: **2 must review**, 0 should review, 0 isolated.

## Must review: on a critical path

- `JobLogController.logDetailCat(HttpServletRequest, long, int)` (added) [LOG_READ]
  - can reach a critical sink
  - `JobLogController.logDetailCat(HttpServletRequest, long, int)` calls `ExecutorBiz.log(LogRequest)`
- `JobLogController.logDetailCat(long, int)` (removed) [LOG_READ]
  - before removal, can reach a critical sink
  - `JobLogController.logDetailCat(long, int)` calls `ExecutorBiz.log(LogRequest)`

## Shape changes: review the structure, not the bodies

- new public method JobLogController.logDetailCat(HttpServletRequest, long, int)
- public method removed JobLogController.logDetailCat(long, int)

## c6a4df2a
# Review manifest

1 methods changed: **1 must review**, 0 should review, 0 isolated.

## Must review: on a critical path

- `JobLogController.logDetailCat(long, int)` (changed) [LOG_READ]
  - can reach a critical sink
  - `JobLogController.logDetailCat(long, int)` calls `ExecutorBiz.log(LogParam)`

## 9293c61c
# Review manifest

3 methods changed: **2 must review**, 0 should review, 1 isolated.

## Must review: on a critical path

- `JobLogController.logDetailCat(long, int)` (added) [LOG_READ]
  - can reach a critical sink
  - `JobLogController.logDetailCat(long, int)` calls `ExecutorBiz.log(LogParam)`
- `JobLogController.logDetailCat(String, long, long, int)` (removed) [LOG_READ]
  - before removal, can reach a critical sink
  - `JobLogController.logDetailCat(String, long, long, int)` calls `ExecutorBiz.log(LogParam)`

## Shape changes: review the structure, not the bodies

- new public method JobLogController.logDetailCat(long, int)
- public method removed JobLogController.logDetailCat(String, long, long, int)

## Isolated: nothing connects these to a sink, body review can be skipped

- `JobLogController.logDetailPage(int, Model)` (changed)
