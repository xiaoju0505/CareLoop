<template>
  <view class="page">
    <view class="app-header" :style="{ paddingTop: statusBar + 'px' }">
      <view class="greeting-text">
        <text class="hello">{{ greeting }}，{{ name || '朋友' }}</text>
        <text class="date">{{ dateLine }}</text>
      </view>
      <view class="header-avatar">{{ initial }}</view>
    </view>

    <scroll-view class="body" scroll-y>
      <view class="card progress-layout">
        <view class="prog-left">
          <text class="prog-h3">今日进度</text>
          <view class="num">{{ doneToday }} <text class="slash">/ {{ todayTotal }}</text></view>
          <text class="desc">已完成采集 · 待办 {{ pendingCount }} 项</text>
        </view>
        <view class="prog-right">
          <text class="percent">{{ progress }}%</text>
          <view class="progress-track">
            <view class="progress-fill" :style="{ width: progress + '%' }" />
          </view>
        </view>
      </view>

      <view v-if="loading" class="empty-state">
        <text class="empty-h4">加载计划中…</text>
      </view>
      <view v-else-if="!tasks.length" class="empty-state">
        <text class="empty-h4">此刻没有待办</text>
        <text class="empty-p">到点后系统会按飞书计划推送术后随访表</text>
      </view>
      <view v-else class="task-block">
        <view class="sec-row">
          <text class="section-title">今日待办</text>
          <text class="sec-link" @click="goChat">去回答</text>
        </view>
        <view
          v-for="(t, i) in tasks"
          :key="t.taskId || i"
          class="card task"
          @click="goChat"
        >
          <view class="check" />
          <view class="task-main">
            <view class="task-top">
              <text class="kind node">术后随访 · D{{ t.dayOffset || 0 }}</text>
              <text class="when">{{ fmtTime(t.scheduledAt) }}</text>
            </view>
            <text class="q">{{ taskTitle(t) }}</text>
          </view>
        </view>
      </view>

      <text class="section-title tips-title">照护提示</text>
      <view class="tips-wrapper">
        <view v-for="(tip, i) in tips" :key="i" class="tip-card">
          <text class="tip-h4">{{ tip.title }}</text>
          <text class="tip-p">{{ tip.body }}</text>
        </view>
      </view>

      <button class="action-btn" @click="goChat">打开随访助手</button>
      <view style="height: 32rpx;" />
    </scroll-view>
  </view>
</template>

<script>
import { fetchSummary } from '../../utils/api.js'
import { getSession } from '../../utils/session.js'

export default {
  data() {
    return {
      statusBar: 20,
      name: '',
      patientId: null,
      loading: true,
      tasks: [],
      tips: [],
      doneToday: 0,
      pendingCount: 0,
      progress: 0
    }
  },
  computed: {
    greeting() {
      const h = new Date().getHours()
      if (h < 11) return '早上好'
      if (h < 14) return '中午好'
      if (h < 18) return '下午好'
      return '晚上好'
    },
    dateLine() {
      const d = new Date()
      const w = ['日', '一', '二', '三', '四', '五', '六'][d.getDay()]
      return d.getMonth() + 1 + '月' + d.getDate() + '日 · 周' + w
    },
    initial() {
      return (this.name || '患').slice(0, 1)
    },
    todayTotal() {
      return Math.max(this.doneToday + this.pendingCount, 1)
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
    this.load()
  },
  methods: {
    isDaily(t) {
      return false
    },
    taskTitle(t) {
      if (t.form && t.form.title) return t.form.title
      return t.question || '请反馈恢复情况'
    },
    fmtTime(s) {
      if (!s) return '待发送'
      const m = String(s).match(/(\d{2}:\d{2})/)
      return m ? m[1] : String(s).slice(5, 16)
    },
    goChat() {
      uni.switchTab({ url: '/pages/chat/chat' })
    },
    async load() {
      this.loading = true
      try {
        const data = await fetchSummary(this.patientId)
        this.tasks = data.pendingTasks || []
        this.tips = data.careTips || []
        this.doneToday = data.doneToday || 0
        this.pendingCount = data.pendingCount || 0
        this.progress = data.todayProgress || 0
        if (data.patient && data.patient.name) this.name = data.patient.name
      } catch (e) {
        uni.showToast({ title: e.message || '加载失败', icon: 'none' })
      } finally {
        this.loading = false
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
.app-header {
  padding: 24rpx 48rpx 16rpx;
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-shrink: 0;
  background: #f3f6f5;
}
.hello {
  display: block;
  font-size: 44rpx;
  font-weight: 700;
  color: #1e293b;
  letter-spacing: -1rpx;
  margin-bottom: 4rpx;
}
.date {
  display: block;
  font-size: 28rpx;
  color: #64748b;
}
.header-avatar {
  width: 76rpx;
  height: 76rpx;
  background: #168b76;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
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
.progress-layout {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 32rpx;
}
.prog-h3 {
  display: block;
  font-size: 26rpx;
  color: #64748b;
  font-weight: 500;
  margin-bottom: 12rpx;
}
.num {
  font-size: 68rpx;
  font-weight: 800;
  color: #1e293b;
  line-height: 1;
  margin-bottom: 4rpx;
}
.slash {
  font-size: 36rpx;
  font-weight: 400;
  color: #94a3b8;
}
.desc {
  display: block;
  font-size: 24rpx;
  color: #64748b;
  margin-top: 8rpx;
}
.prog-right {
  width: 120rpx;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}
.percent {
  font-size: 30rpx;
  font-weight: 700;
  color: #168b76;
  margin-bottom: 12rpx;
}
.progress-track {
  width: 100%;
  height: 8rpx;
  background: #edf2f7;
  border-radius: 8rpx;
  overflow: hidden;
}
.progress-fill {
  height: 100%;
  background: #168b76;
  border-radius: 8rpx;
}
.empty-state {
  text-align: center;
  padding: 48rpx 0 40rpx;
  border: 2rpx dashed #e2e8f0;
  border-radius: 40rpx;
  background: #fafcfb;
  margin-bottom: 16rpx;
}
.empty-h4 {
  display: block;
  color: #1e293b;
  font-size: 30rpx;
  font-weight: 600;
  margin-bottom: 8rpx;
}
.empty-p {
  display: block;
  color: #94a3b8;
  font-size: 26rpx;
  padding: 0 32rpx;
  line-height: 1.5;
}
.sec-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16rpx;
}
.section-title {
  display: block;
  font-size: 32rpx;
  font-weight: 600;
  color: #1e293b;
}
.tips-title {
  margin-top: 32rpx;
  margin-bottom: 16rpx;
}
.sec-link {
  font-size: 26rpx;
  color: #168b76;
  font-weight: 500;
}
.task-block {
  margin-bottom: 8rpx;
}
.task {
  display: flex;
  gap: 20rpx;
  padding: 28rpx 32rpx;
  margin-bottom: 16rpx;
}
.check {
  width: 32rpx;
  height: 32rpx;
  margin-top: 8rpx;
  border-radius: 50%;
  border: 3rpx solid #168b76;
  flex-shrink: 0;
}
.task-main {
  flex: 1;
  min-width: 0;
}
.task-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12rpx;
}
.kind {
  font-size: 22rpx;
  font-weight: 600;
  padding: 4rpx 14rpx;
  border-radius: 8rpx;
}
.kind.daily {
  background: #fef3c7;
  color: #b45309;
}
.kind.node {
  background: #e8f5f2;
  color: #0f6b5a;
}
.when {
  font-size: 22rpx;
  color: #94a3b8;
}
.q {
  display: block;
  margin-top: 12rpx;
  font-size: 28rpx;
  line-height: 1.5;
  color: #1e293b;
}
.tips-wrapper {
  display: flex;
  gap: 24rpx;
}
.tip-card {
  flex: 1;
  background: #1e293b;
  border-radius: 32rpx;
  padding: 32rpx 28rpx;
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.tip-h4 {
  font-size: 26rpx;
  font-weight: 600;
  color: #fff;
  margin-bottom: 12rpx;
  letter-spacing: 0.5rpx;
}
.tip-p {
  font-size: 22rpx;
  font-weight: 300;
  color: #94a3b8;
  line-height: 1.5;
}
.action-btn {
  width: 100%;
  background: #168b76;
  border: none;
  color: #fff;
  font-size: 32rpx;
  font-weight: 600;
  border-radius: 60rpx;
  height: 96rpx;
  line-height: 96rpx;
  box-shadow: 0 12rpx 32rpx rgba(22, 139, 118, 0.25);
  margin-top: 24rpx;
  letter-spacing: 1rpx;
}
</style>
