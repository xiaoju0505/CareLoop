<script>
export default {
  onLaunch() {
    try {
      const raw = uni.getStorageSync('care_app_session')
      if (raw) {
        const s = typeof raw === 'string' ? JSON.parse(raw) : raw
        if (s && s.patientId && s.code) {
          uni.reLaunch({ url: '/pages/today/today' })
        }
      }
    } catch (e) {
      // ignore
    }
  }
}
</script>

<style lang="scss">
@import './uni.scss';

page {
  background: $bg;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica,
    'PingFang SC', 'Microsoft YaHei', Arial, sans-serif;
  color: $text-main;
  font-size: 28rpx;
  -webkit-font-smoothing: antialiased;
}
</style>
