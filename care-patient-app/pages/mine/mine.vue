<template>
  <view class="page">
    <view class="head" :style="{ paddingTop: statusBar + 'px' }">
      <view class="profile">
        <view>
          <text class="hello">{{ name || '患者' }}</text>
          <text class="meta">{{ diagnosis || '—' }} · 码 {{ code }}</text>
        </view>
        <view class="header-avatar">{{ initial }}</view>
      </view>
    </view>

    <scroll-view class="body" scroll-y>
      <view class="card plan-card">
        <view class="stats-grid">
          <view class="stat-item">
            <text class="num">D{{ postopDay }}</text>
            <text class="label">术后天数</text>
          </view>
          <view class="stat-item">
            <text class="num">{{ answeredTotal }}</text>
            <text class="label">已答问卷</text>
          </view>
          <view class="stat-item">
            <text class="num">{{ pendingCount }}</text>
            <text class="label">待办</text>
          </view>
        </view>
        <view class="info-block">
          <view class="info-line">
            <text>计划</text>
            <text class="v">{{ planTitle }}</text>
          </view>
          <view class="info-line">
            <text>状态</text>
            <text class="v">{{ planStatus }}</text>
          </view>
          <view class="info-line">
            <text>下次采集</text>
            <text class="v">{{ nextCollect }}</text>
          </view>
          <view class="info-line" v-if="dischargeAt">
            <text>出院日</text>
            <text class="v">{{ dischargeAt }}</text>
          </view>
        </view>
        <view v-if="extras.length" class="extras">
          <text class="ex-title">医生加项</text>
          <text v-for="(e, i) in extras" :key="i" class="ex-item">· {{ e }}</text>
        </view>
      </view>

      <view class="sec-row">
        <text class="section-title">历史记录</text>
        <text class="reload" @click="load">刷新</text>
      </view>

      <view v-if="!timeline.length" class="empty">暂无回答记录</view>
      <view class="timeline-container">
        <view v-for="(item, i) in timeline" :key="item.id || i" class="timeline-item">
          <view class="rail">
            <view class="timeline-dot" :class="dotClass(item.direction)" />
            <view v-if="i < timeline.length - 1" class="rail-line" />
          </view>
          <view class="timeline-content" :class="{ warn: isIn(item.direction) }">
            <view class="role">
              <text :class="{ 'warn-text': isIn(item.direction) }">{{ dirLabel(item.direction) }}</text>
              <text class="tm">{{ fmtAt(item.createdAt) }}</text>
            </view>
            <text class="text">{{ item.content }}</text>
            <text v-if="item.choiceHint" class="choice">{{ item.choiceHint }}</text>
          </view>
        </view>
      </view>

      <view class="logout" @click="logout">退出登录</view>
      <view style="height: 48rpx;" />
    </scroll-view>
  </view>
</template>

<script>
import { fetchSummary, fetchTimeline } from '../../utils/api.js'
import { getSession, clearSession } from '../../utils/session.js'

export default {
  data() {
    return {
      statusBar: 20,
      patientId: null,
      name: '',
      diagnosis: '',
      code: '',
      postopDay: 0,
      answeredTotal: 0,
      pendingCount: 0,
      planTitle: '—',
      planStatus: '—',
      nextCollect: '暂无',
      dischargeAt: '',
      extras: [],
      timeline: []
    }
  },
  computed: {
    initial() {
      return (this.name || '患').slice(0, 1)
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
    this.name = s.name || ''
    this.diagnosis = s.diagnosis || ''
    this.code = s.code || ''
    this.load()
  },
  methods: {
    isIn(d) {
      return String(d || '').toUpperCase() === 'IN'
    },
    dirLabel(d) {
      return this.isIn(d) ? '我的回答' : '随访助手'
    },
    dotClass(d) {
      return this.isIn(d) ? 'warning' : 'primary'
    },
    fmtAt(s) {
      if (!s) return ''
      return String(s).replace('T', ' ').slice(0, 16)
    },
    parseChoice(structured) {
      if (!structured) return ''
      try {
        const j = typeof structured === 'string' ? JSON.parse(structured) : structured
        if (!j) return ''
        if (Array.isArray(j.selections)) {
          return j.selections.map((s) => {
            const c = (s.chosen || []).join('、')
            return (s.label || '') + '：' + c + (s.custom ? '（' + s.custom + '）' : '')
          }).join('；')
        }
      } catch (e) {
        return ''
      }
      return ''
    },
    async load() {
      try {
        const [sum, tl] = await Promise.all([
          fetchSummary(this.patientId),
          fetchTimeline(this.patientId)
        ])
        if (sum.patient) {
          this.name = sum.patient.name || this.name
          this.diagnosis = sum.patient.diagnosis || this.diagnosis
        }
        const plan = sum.plan || {}
        this.postopDay = plan.postopDay || 0
        this.answeredTotal = sum.answeredTotal || 0
        this.pendingCount = sum.pendingCount || 0
        this.planTitle = plan.title || '出院随访计划'
        this.planStatus = plan.status || '—'
        this.nextCollect = plan.nextCollectAt
          ? String(plan.nextCollectAt).replace('T', ' ').slice(0, 16)
          : '暂无待办'
        this.dischargeAt = plan.dischargeAt
          ? String(plan.dischargeAt).replace('T', ' ').slice(0, 10)
          : ''
        this.extras = plan.doctorExtras || []
        this.timeline = (tl || []).map((item) => ({
          ...item,
          choiceHint: this.parseChoice(item.structured)
        }))
      } catch (e) {
        uni.showToast({ title: e.message || '加载失败', icon: 'none' })
      }
    },
    logout() {
      uni.showModal({
        title: '退出登录',
        content: '确定退出当前病患账号？',
        success: (res) => {
          if (!res.confirm) return
          clearSession()
          uni.reLaunch({ url: '/pages/login/login' })
        }
      })
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
.profile {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.hello {
  display: block;
  font-size: 44rpx;
  font-weight: 700;
  color: #1e293b;
  letter-spacing: -1rpx;
}
.meta {
  display: block;
  margin-top: 8rpx;
  font-size: 26rpx;
  color: #64748b;
  max-width: 480rpx;
}
.header-avatar {
  width: 76rpx;
  height: 76rpx;
  background: #168b76;
  border-radius: 50%;
  color: #fff;
  text-align: center;
  line-height: 76rpx;
  font-size: 30rpx;
  font-weight: 600;
  box-shadow: 0 8rpx 24rpx rgba(22, 139, 118, 0.2);
}
.body {
  flex: 1;
  height: 0;
  padding: 24rpx 32rpx 0;
  box-sizing: border-box;
}
.card {
  background: #fff;
  border-radius: 40rpx;
  padding: 40rpx;
  box-shadow: 0 24rpx 64rpx rgba(0, 0, 0, 0.03), 0 8rpx 16rpx rgba(0, 0, 0, 0.02);
}
.plan-card {
  padding-bottom: 20rpx;
}
.stats-grid {
  display: flex;
  gap: 16rpx;
}
.stat-item {
  flex: 1;
  text-align: center;
  padding: 24rpx 0;
  background: #fafcfb;
  border-radius: 24rpx;
}
.num {
  display: block;
  font-size: 48rpx;
  font-weight: 700;
  color: #168b76;
}
.label {
  display: block;
  font-size: 24rpx;
  color: #64748b;
  margin-top: 8rpx;
}
.info-block {
  margin-top: 32rpx;
  padding-top: 16rpx;
  border-top: 2rpx solid #f1f5f9;
}
.info-line {
  display: flex;
  justify-content: space-between;
  gap: 24rpx;
  padding: 20rpx 0;
  border-bottom: 2rpx solid #f1f5f9;
  font-size: 28rpx;
  color: #1e293b;
}
.info-line:last-child {
  border-bottom: 0;
  padding-bottom: 0;
}
.v {
  flex: 1;
  text-align: right;
  color: #64748b;
  font-weight: 400;
}
.extras {
  margin-top: 24rpx;
  padding: 24rpx;
  background: #fafcfb;
  border-radius: 20rpx;
}
.ex-title {
  display: block;
  font-size: 24rpx;
  font-weight: 700;
  color: #0f6b5a;
  margin-bottom: 8rpx;
}
.ex-item {
  display: block;
  font-size: 26rpx;
  color: #1e293b;
  line-height: 1.55;
  margin-top: 6rpx;
}
.sec-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin: 40rpx 0 16rpx;
}
.section-title {
  font-size: 32rpx;
  font-weight: 600;
  color: #1e293b;
}
.reload {
  font-size: 26rpx;
  font-weight: 500;
  color: #168b76;
}
.empty {
  padding: 40rpx;
  text-align: center;
  color: #94a3b8;
  font-size: 26rpx;
}
.timeline-container {
  margin-top: 8rpx;
}
.timeline-item {
  display: flex;
  gap: 24rpx;
  margin-bottom: 32rpx;
}
.rail {
  width: 24rpx;
  display: flex;
  flex-direction: column;
  align-items: center;
  flex-shrink: 0;
}
.rail-line {
  flex: 1;
  width: 4rpx;
  background: #e2e8f0;
  margin-top: 8rpx;
  min-height: 40rpx;
}
.timeline-dot {
  width: 24rpx;
  height: 24rpx;
  border-radius: 50%;
  background: #cbd5e0;
  flex-shrink: 0;
  margin-top: 12rpx;
  border: 4rpx solid #fff;
  box-sizing: border-box;
}
.timeline-dot.primary {
  background: #168b76;
}
.timeline-dot.warning {
  background: #ed8936;
}
.timeline-content {
  background: #fff;
  border-radius: 32rpx;
  padding: 32rpx;
  box-shadow: 0 24rpx 64rpx rgba(0, 0, 0, 0.03), 0 8rpx 16rpx rgba(0, 0, 0, 0.02);
  flex: 1;
  min-width: 0;
}
.timeline-content.warn {
  background: #fffff0;
}
.role {
  display: flex;
  justify-content: space-between;
  font-size: 24rpx;
  font-weight: 600;
  color: #1e293b;
  margin-bottom: 8rpx;
}
.warn-text {
  color: #d69e2e;
}
.tm {
  font-weight: 400;
  color: #94a3b8;
}
.text {
  font-size: 28rpx;
  color: #64748b;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
}
.choice {
  display: block;
  margin-top: 12rpx;
  font-size: 24rpx;
  color: #94a3b8;
  line-height: 1.45;
}
.logout {
  margin-top: 48rpx;
  text-align: center;
  color: #ef4444;
  font-size: 28rpx;
  font-weight: 600;
  padding: 24rpx;
}
</style>
