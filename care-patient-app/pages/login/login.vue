<template>
  <view class="page">
    <view class="login-layout">
      <view class="brand-header">
        <text class="h1">CareLoop</text>
        <text class="h2">出院后的连续照护</text>
        <text class="p">输入医生在飞书发放的 8 位专属病患码，按计划完成术后随访。</text>
      </view>

      <view class="login-card">
        <view class="form-group">
          <text class="label">病患码</text>
          <view class="otp-wrap" :class="{ focus: focused }">
            <input
              class="code"
              type="number"
              maxlength="8"
              :value="code"
              placeholder="• • • • • • • •"
              placeholder-class="ph"
              @input="onInput"
              @focus="focused = true"
              @blur="focused = false"
              @confirm="doLogin"
            />
          </view>
        </view>
        <button class="primary-btn" :disabled="loading" :loading="loading" @click="doLogin">
          进入今日计划
        </button>
        <view class="err">{{ err }}</view>
      </view>

      <view class="login-footer">接口{{ apiHint }}</view>
    </view>
  </view>
</template>

<script>
import { loginByCode } from '../../utils/api.js'
import { saveSession } from '../../utils/session.js'
import { API_BASE } from '../../utils/config.js'

export default {
  data() {
    return {
      code: '',
      err: '',
      loading: false,
      focused: false,
      apiHint: API_BASE || 'H5代理 → fromfreedom.top'
    }
  },
  methods: {
    onInput(e) {
      this.code = String(e.detail.value || '').replace(/\D/g, '').slice(0, 8)
    },
    async doLogin() {
      this.err = ''
      if (this.code.length !== 8) {
        this.err = '请输入完整的 8 位数字病患码'
        return
      }
      this.loading = true
      try {
        const data = await loginByCode(this.code)
        saveSession({
          patientId: data.patientId,
          name: data.patientName,
          diagnosis: data.diagnosis,
          code: data.code || this.code
        })
        uni.setStorageSync('care_app_welcome', data.welcome || '')
        uni.reLaunch({ url: '/pages/today/today' })
      } catch (e) {
        this.err = e.message || '登录失败'
      } finally {
        this.loading = false
      }
    }
  }
}
</script>

<style scoped lang="scss">
.page {
  min-height: 100vh;
  background: radial-gradient(circle at 100% -10%, #cce4e1 0%, #f3f6f5 80%);
  display: flex;
  flex-direction: column;
  padding: 96rpx 48rpx 60rpx;
  box-sizing: border-box;
}
.login-layout {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding-bottom: 120rpx;
}
.brand-header {
  margin-bottom: 64rpx;
}
.h1 {
  display: block;
  font-size: 64rpx;
  font-weight: 700;
  color: #1e293b;
  letter-spacing: -1rpx;
  margin-bottom: 12rpx;
}
.h2 {
  display: block;
  font-size: 32rpx;
  font-weight: 600;
  color: #168b76;
  margin-bottom: 24rpx;
}
.p {
  display: block;
  font-size: 28rpx;
  color: #64748b;
  line-height: 1.6;
}
.login-card {
  background: #fff;
  border-radius: 40rpx;
  padding: 48rpx;
  box-shadow: 0 24rpx 64rpx rgba(0, 0, 0, 0.04), 0 8rpx 16rpx rgba(0, 0, 0, 0.02);
}
.form-group {
  margin-bottom: 48rpx;
}
.label {
  display: block;
  font-size: 28rpx;
  font-weight: 600;
  color: #1e293b;
  margin-bottom: 20rpx;
}
.otp-wrap {
  display: flex;
  align-items: center;
  background: #f8fafc;
  border: 2rpx solid #e2e8f0;
  border-radius: 24rpx;
  transition: all 0.2s ease;
}
.otp-wrap.focus {
  border-color: #168b76;
  background: #fff;
  box-shadow: 0 0 0 8rpx #e8f5f2;
}
.code {
  width: 100%;
  padding: 32rpx;
  font-size: 44rpx;
  font-weight: 600;
  color: #1e293b;
  letter-spacing: 20rpx;
  text-align: center;
}
.ph {
  color: #cbd5e0;
  font-weight: 400;
  letter-spacing: 16rpx;
  font-size: 36rpx;
}
.primary-btn {
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
}
.primary-btn[disabled] {
  opacity: 0.55;
}
.err {
  min-height: 40rpx;
  margin-top: 20rpx;
  text-align: center;
  color: #ef4444;
  font-size: 26rpx;
}
.login-footer {
  margin-top: 80rpx;
  text-align: center;
  font-size: 22rpx;
  color: #cbd5e0;
  letter-spacing: 2rpx;
}
</style>
