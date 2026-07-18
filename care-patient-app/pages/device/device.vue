<template>
  <view class="page">
    <view class="head" :style="{ paddingTop: statusBar + 'px' }">
      <text class="section-title">CareLoop</text>
      <text class="sub">外接设备 · 模拟健康手环，数据写入随访报告</text>
    </view>

    <scroll-view class="body" scroll-y>
      <view v-if="!deviceConnected" class="card device-card">
        <view class="device-sphere" />
        <text class="device-status disconnected">{{ connectStatus }}</text>
        <text class="device-desc">连接后同步心率、血压、血氧、睡眠等指标，供医生分诊参考。</text>
        <button class="device-btn" :disabled="deviceLoading" @click="doSync">
          {{ deviceLoading ? '连接中…' : '连接手环' }}
        </button>
      </view>
      <view v-else>
        <view class="card status-bar">
          <view class="dot" />
          <text class="ok">已连接 · 模拟数据</text>
          <text class="sync">{{ lastSync }}</text>
        </view>
        <view class="grid">
          <view v-for="(it, idx) in metrics" :key="idx" class="card metric">
            <text class="lab">{{ it.label }}</text>
            <text class="val">{{ it.value }}</text>
            <text class="unit">{{ it.unit }}</text>
          </view>
        </view>
        <button class="device-btn ghost" :disabled="deviceLoading" @click="doSync">再次同步</button>
      </view>
    </scroll-view>
  </view>
</template>

<script>
import { syncDevice } from '../../utils/api.js'
import { getSession } from '../../utils/session.js'

export default {
  data() {
    return {
      statusBar: 20,
      patientId: null,
      deviceConnected: false,
      deviceLoading: false,
      connectStatus: '未连接',
      metrics: [],
      lastSync: '—'
    }
  },
  onShow() {
    const sys = uni.getSystemInfoSync()
    this.statusBar = sys.statusBarHeight || 20
    const s = getSession()
    if (!s || !s.patientId) {
      uni.reLaunch({ url: '/pages/login/login' })
      return
    }
    this.patientId = s.patientId
  },
  methods: {
    buildMetrics(d) {
      return [
        { label: '心率', value: d.heartRate, unit: 'bpm' },
        { label: '静息心率', value: d.restingHeartRate, unit: 'bpm' },
        { label: '血压', value: d.bloodPressure, unit: 'mmHg' },
        { label: '血氧', value: d.spo2, unit: '%' },
        { label: '步数', value: d.steps, unit: '今日' },
        { label: '热量', value: d.calories, unit: 'kcal' },
        { label: '睡眠', value: d.sleepHours, unit: '小时' },
        { label: '深睡', value: d.sleepDeepMin, unit: '分钟' },
        { label: 'HRV', value: d.hrvRmssd, unit: 'ms' },
        { label: '皮温', value: d.skinTemp, unit: '℃' },
        { label: '压力', value: d.stressScore, unit: '分' },
        { label: 'REM', value: d.sleepRemMin, unit: '分钟' }
      ]
    },
    async doSync() {
      if (!this.patientId || this.deviceLoading) return
      this.deviceLoading = true
      this.connectStatus = '连接中…'
      try {
        await new Promise((r) => setTimeout(r, 500))
        const data = await syncDevice(this.patientId)
        this.deviceConnected = true
        this.metrics = this.buildMetrics(data)
        this.lastSync = data.syncedAt || '刚刚'
      } catch (e) {
        this.connectStatus = '未连接'
        uni.showToast({ title: e.message || '同步失败', icon: 'none' })
      } finally {
        this.deviceLoading = false
      }
    }
  }
}
</script>

<style scoped lang="scss">
.page {
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: #f3f6f5;
}
.head {
  padding: 16rpx 32rpx 8rpx;
  background: #f3f6f5;
}
.section-title {
  display: block;
  font-size: 32rpx;
  font-weight: 600;
  color: #1e293b;
}
.sub {
  display: block;
  margin-top: 8rpx;
  font-size: 26rpx;
  color: #64748b;
}
.body {
  flex: 1;
  height: 0;
  padding: 24rpx 32rpx 40rpx;
  box-sizing: border-box;
}
.card {
  background: #fff;
  border-radius: 40rpx;
  box-shadow: 0 24rpx 64rpx rgba(0, 0, 0, 0.03), 0 8rpx 16rpx rgba(0, 0, 0, 0.02);
}
.device-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 80rpx 40rpx 60rpx;
}
.device-sphere {
  width: 168rpx;
  height: 168rpx;
  background: radial-gradient(circle at 35% 30%, #3db8a5, #168b76);
  border-radius: 50%;
  box-shadow: 0 16rpx 48rpx rgba(22, 139, 118, 0.25);
  margin-bottom: 40rpx;
}
.device-status {
  font-size: 36rpx;
  font-weight: 600;
  color: #1e293b;
  margin-bottom: 12rpx;
}
.device-status.disconnected {
  color: #ef4444;
  font-weight: 500;
}
.device-desc {
  color: #64748b;
  font-size: 28rpx;
  line-height: 1.5;
  text-align: center;
  margin-bottom: 48rpx;
  max-width: 520rpx;
}
.device-btn {
  width: 100%;
  background: #168b76;
  border: none;
  border-radius: 60rpx;
  height: 92rpx;
  line-height: 92rpx;
  color: #fff;
  font-weight: 600;
  font-size: 30rpx;
  box-shadow: 0 8rpx 32rpx rgba(22, 139, 118, 0.2);
}
.device-btn.ghost {
  background: #fff;
  color: #168b76;
  border: 2rpx solid #168b76;
  box-shadow: none;
  margin-top: 32rpx;
}
.status-bar {
  display: flex;
  align-items: center;
  gap: 12rpx;
  padding: 28rpx 32rpx;
  margin-bottom: 24rpx;
}
.dot {
  width: 14rpx;
  height: 14rpx;
  border-radius: 50%;
  background: #168b76;
}
.ok {
  font-size: 28rpx;
  color: #0f6b5a;
  font-weight: 600;
}
.sync {
  margin-left: auto;
  font-size: 22rpx;
  color: #94a3b8;
}
.grid {
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx;
}
.metric {
  width: calc(50% - 8rpx);
  padding: 28rpx 24rpx;
  box-sizing: border-box;
}
.lab {
  display: block;
  font-size: 22rpx;
  color: #64748b;
}
.val {
  display: block;
  margin-top: 8rpx;
  font-size: 40rpx;
  font-weight: 700;
  color: #1e293b;
}
.unit {
  display: block;
  margin-top: 4rpx;
  font-size: 22rpx;
  color: #94a3b8;
}
</style>
