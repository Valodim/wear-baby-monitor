This will become a Baby Monitor App using WearOS.

## Wear OS constraints
- Continuous microphone monitoring should run as a foreground service to avoid background restrictions.
- Wear OS may stop background work when the watch is idle or low on battery; expect gaps if the user enables battery saver.
- Always provide a visible notification while monitoring to keep the service alive.
