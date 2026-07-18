<template>
  <view class="shell">
    <view class="header" :style="{ paddingTop: statusBar + 'px' }">
      <view class="header-row">
        <view>
          <text class="section-title">随访助手</text>
          <text class="patient">{{ patientLine }}</text>
        </view>
        <view class="hdr-btn" @click="refreshTasks">↻</view>
      </view>
    </view>

    <scroll-view
      class="content"
      scroll-y
      :scroll-into-view="scrollInto"
      :scroll-with-animation="true"
    >
      <view class="chat-list">
        <view v-if="!messages.length" class="empty">
          <text class="empty-title">正在加载随访计划…</text>
          <text class="empty-sub">到点后系统会推送术后随访表</text>
        </view>
        <view
          v-for="(m, i) in messages"
          :id="'m' + i"
          :key="i"
          class="msg"
          :class="m.role"
        >
          <view class="bubble" :class="m.role">
            <view v-if="m.tag" class="tag">{{ m.tag }}</view>
            <text class="body">{{ m.text }}</text>
            <text class="time">{{ m.time }}</text>
          </view>
          <view v-if="m.form && m.form.items && m.form.items.length" class="inline-form">
            <view class="form-prompt">{{ m.form.prompt || '请逐项选择，并可补充描述' }}</view>
            <view v-if="m.active && skipOpen" class="skip-panel">
              <view class="flabel">今日跳过原因</view>
              <view class="opt-row">
                <view
                  v-for="r in (m.form.skipReasons || defaultSkipReasons)"
                  :key="r"
                  class="opt"
                  :class="{ on: skipReason === r }"
                  @click="skipReason = r"
                >{{ r }}</view>
              </view>
              <view class="skip-actions">
                <view class="skip-cancel" @click="skipOpen = false; skipReason = ''">取消</view>
                <view class="form-submit skip-confirm" @click="sendSkip">确认跳过</view>
              </view>
            </view>
            <template v-else>
            <view
              v-for="(item, idx) in m.form.items"
              :key="item.id || idx"
              v-show="itemVisible(item)"
              class="form-item"
            >
              <view v-if="item.type === 'section'" class="section-label">{{ item.label }}</view>
              <template v-else>
              <view class="flabel">{{ item.label }}{{ item.multi ? '（可多选）' : '' }}</view>
              <view class="opt-row">
                <view
                  v-for="opt in (item.options || [])"
                  :key="opt"
                  class="opt"
                  :class="{ on: m.active && isChosen(idx, opt), done: !m.active && isFrozen(m, idx, opt) }"
                  @click="m.active && toggleOpt(idx, opt, item)"
                >{{ opt }}</view>
              </view>
              <input
                v-if="(item.allowCustom !== false || item.type === 'text') && m.active"
                class="custom"
                :placeholder="item.customHint || '补充描述（选填）'"
                :value="customs[idx] || ''"
                @input="onCustom(idx, $event)"
              />
              <view v-else-if="m.frozenCustoms && m.frozenCustoms[idx]" class="custom-done">
                补充：{{ m.frozenCustoms[idx] }}
              </view>
              </template>
            </view>
            <view v-if="m.active" class="form-actions">
              <view class="skip-link" @click="skipOpen = true">今日跳过</view>
              <view class="form-submit" @click="send">提交本次回答</view>
            </view>
            </template>
          </view>
        </view>
        <view id="bottomAnchor" style="height: 24rpx;"></view>
      </view>
    </scroll-view>

    <view class="input-bar" :style="{ paddingBottom: safeBottom + 'px' }">
      <view class="input-wrap">
        <textarea
          class="ta"
          v-model="input"
          :disabled="processing"
          auto-height
          maxlength="500"
          placeholder="其他补充说明（选填）..."
          :show-confirm-bar="false"
          @confirm="send"
        />
      </view>
      <view class="send" :class="{ off: processing || !canSend }" @click="send">➤</view>
    </view>
  </view>
</template>

<script>
import { listTasks, sendChat } from '../../utils/api.js'
import { getSession } from '../../utils/session.js'

export default {
  data() {
    return {
      statusBar: 20,
      safeBottom: 8,
      patientId: null,
      name: '',
      diagnosis: '',
      code: '',
      messages: [],
      input: '',
      processing: false,
      pendingTaskId: null,
      askedTaskIds: [],
      scrollInto: '',
      activeMsgIndex: -1,
      formItems: [],
      chosen: {},
      customs: {},
      booted: false,
      skipOpen: false,
      skipReason: '',
      defaultSkipReasons: ['太累/疼痛无法填写', '外出不在家', '已住院/急诊', '其他']
    }
  },
  computed: {
    patientLine() {
      return (this.name || '患者') + ' · ' + (this.diagnosis || '') + ' · 码 ' + this.code
    },
    canSend() {
      if ((this.input || '').trim()) return true
      return Object.keys(this.chosen).some((k) => (this.chosen[k] || []).length)
        || Object.keys(this.customs).some((k) => (this.customs[k] || '').trim())
    }
  },
  onShow() {
    const sys = uni.getSystemInfoSync()
    this.statusBar = sys.statusBarHeight || 20
    this.safeBottom = (sys.safeAreaInsets && sys.safeAreaInsets.bottom) || 8
    const s = getSession()
    if (!s || !s.patientId) {
      uni.reLaunch({ url: '/pages/login/login' })
      return
    }
    this.patientId = s.patientId
    this.name = s.name || ''
    this.diagnosis = s.diagnosis || ''
    this.code = s.code || ''
    if (!this.booted) {
      this.booted = true
      const welcome = uni.getStorageSync('care_app_welcome')
      uni.removeStorageSync('care_app_welcome')
      this.addBubble(
        'system',
        welcome ||
          ('您好，' + this.name + '。我将按医生在飞书确认的计划，用一张随访表收集您的恢复情况。')
      )
      this.pushNextPlanQuestion({ announceIdle: true, force: true })
    }
  },
  methods: {
    timeNow() {
      const d = new Date()
      return String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0')
    },
    addBubble(role, text, tag, form) {
      if (form && form.items) {
        this.messages.forEach((m) => {
          if (m.active) m.active = false
        })
      }
      const msg = {
        role,
        text: String(text || ''),
        tag: tag || '',
        time: this.timeNow(),
        form: form && form.items ? form : null,
        active: !!(form && form.items && form.items.length),
        frozenChosen: null,
        frozenCustoms: null
      }
      this.messages.push(msg)
      this.activeMsgIndex = msg.active ? this.messages.length - 1 : this.activeMsgIndex
      if (msg.active) {
        this.formItems = form.items
        this.chosen = {}
        this.customs = {}
        this.skipOpen = false
        this.skipReason = ''
      }
      this.$nextTick(() => {
        this.scrollInto = ''
        this.$nextTick(() => {
          this.scrollInto = 'bottomAnchor'
        })
      })
    },
    isDaily(task) {
      return false
    },
    taskTag(task) {
      return '术后随访 · D' + (task.dayOffset || '')
    },
    isChosen(idx, opt) {
      return (this.chosen[idx] || []).indexOf(opt) >= 0
    },
    isFrozen(m, idx, opt) {
      const arr = (m.frozenChosen && m.frozenChosen[idx]) || []
      return arr.indexOf(opt) >= 0
    },
    isNoneOpt(opt) {
      return !opt ? false : (opt === '无以上情况' || opt.indexOf('无以上') === 0)
    },
    itemVisible(item) {
      if (!item || !item.showIf) return true
      const cond = item.showIf
      const keys = Object.keys(cond)
      for (let i = 0; i < keys.length; i++) {
        const key = keys[i]
        const want = cond[key] || []
        const srcIdx = this.formItems.findIndex((it) => it && it.id === key)
        const chosen = srcIdx >= 0 ? (this.chosen[srcIdx] || []) : []
        const hit = want.some((v) => chosen.indexOf(v) >= 0)
        if (!hit) return false
      }
      return true
    },
    toggleOpt(idx, opt, item) {
      const multi = !!(item && item.multi)
      const exclusive = !!(item && item.exclusive)
      let cur = this.chosen[idx] ? this.chosen[idx].slice() : []
      const i = cur.indexOf(opt)
      if (exclusive || !multi) {
        if (exclusive && multi) {
          if (this.isNoneOpt(opt)) {
            cur = [opt]
          } else {
            cur = cur.filter((o) => !this.isNoneOpt(o))
            if (i >= 0) cur.splice(i, 1)
            else cur.push(opt)
          }
        } else {
          cur = [opt]
        }
      } else {
        if (i >= 0) cur.splice(i, 1)
        else cur.push(opt)
      }
      this.chosen = { ...this.chosen, [idx]: cur }
    },
    onCustom(idx, e) {
      this.customs = { ...this.customs, [idx]: e.detail.value }
    },
    buildSelections() {
      const selections = []
      this.formItems.forEach((item, idx) => {
        if (!item || item.type === 'section') return
        if (!this.itemVisible(item)) return
        const chosen = this.chosen[idx] || []
        const custom = (this.customs[idx] || '').trim()
        if (!chosen.length && !custom) return
        selections.push({
          id: item.id || String(idx),
          label: item.label || ('项目' + (idx + 1)),
          chosen,
          custom
        })
      })
      return selections
    },
    missingRequired() {
      const missing = []
      this.formItems.forEach((item, idx) => {
        if (!item || item.type === 'section') return
        if (!this.itemVisible(item)) return
        const required = item.required !== false && item.type !== 'text'
        if (!required) return
        const chosen = this.chosen[idx] || []
        const custom = (this.customs[idx] || '').trim()
        if (!chosen.length && !custom) missing.push(item.label || item.id || '未命名项')
      })
      return missing
    },
    freezeActiveForm() {
      if (this.activeMsgIndex < 0) return
      const m = this.messages[this.activeMsgIndex]
      if (!m) return
      m.active = false
      m.frozenChosen = { ...this.chosen }
      m.frozenCustoms = { ...this.customs }
      this.formItems = []
      this.chosen = {}
      this.customs = {}
      this.skipOpen = false
      this.skipReason = ''
      this.activeMsgIndex = -1
    },
    async pushNextPlanQuestion(opts) {
      opts = opts || {}
      if (!this.patientId) return
      try {
        const tasks = await listTasks(this.patientId)
        if (!Array.isArray(tasks) || !tasks.length) {
          if (opts.announceIdle) {
            this.addBubble(
              'system',
              '当前没有到期待办。到点后系统会按飞书随访计划自动发送问题，请届时打开本页回答。'
            )
          }
          this.pendingTaskId = null
          return
        }
        let next = tasks.find((t) => this.askedTaskIds.indexOf(String(t.taskId)) < 0)
        if (!next) next = tasks[0]
        this.pendingTaskId = next.taskId
        const id = String(next.taskId)
        if (this.askedTaskIds.indexOf(id) < 0 || opts.force) {
          if (this.askedTaskIds.indexOf(id) < 0) this.askedTaskIds.push(id)
          const prompt = next.form && next.form.title
            ? String(next.form.title)
            : (next.question || '请反馈今日恢复情况')
          this.addBubble('system', prompt, this.taskTag(next), next.form || null)
        }
      } catch (e) {
        this.addBubble('system', '加载任务失败：' + (e.message || e))
      }
    },
    async sendSkip() {
      if (this.processing || !this.patientId) return
      if (!this.skipReason) {
        uni.showToast({ title: '请选择跳过原因', icon: 'none' })
        return
      }
      this.processing = true
      const body = {
        patientId: String(this.patientId),
        skipped: true,
        skipReason: this.skipReason
      }
      if (this.pendingTaskId != null) body.taskId = String(this.pendingTaskId)
      const display = '【今日跳过】' + this.skipReason
      this.freezeActiveForm()
      this.addBubble('user', display)
      try {
        const data = await sendChat(this.patientId, body)
        if (data.assistantReply) this.addBubble('system', data.assistantReply)
        if (this.pendingTaskId != null) {
          const id = String(this.pendingTaskId)
          if (this.askedTaskIds.indexOf(id) < 0) this.askedTaskIds.push(id)
        }
        this.pendingTaskId = null
        await this.pushNextPlanQuestion({ announceIdle: true })
      } catch (e) {
        this.addBubble('system', '发送失败：' + (e.message || e))
      } finally {
        this.processing = false
      }
    },
    async send() {
      if (this.processing || !this.canSend || !this.patientId) return
      if (this.formItems && this.formItems.length) {
        const missing = this.missingRequired()
        if (missing.length) {
          uni.showToast({ title: '请完成：' + missing[0], icon: 'none', duration: 2500 })
          return
        }
      }
      this.processing = true
      const selections = this.buildSelections()
      const note = (this.input || '').trim()
      let display = note
      const body = { patientId: String(this.patientId) }
      if (this.pendingTaskId != null) body.taskId = String(this.pendingTaskId)
      if (selections.length) {
        body.selections = selections
        body.note = note
        display = selections.map((s) => {
          const c = (s.chosen || []).join('、')
          return s.label + '：' + c + (s.custom ? '（' + s.custom + '）' : '')
        }).join('；') + (note ? '；补充：' + note : '')
      } else {
        body.text = note
      }
      this.freezeActiveForm()
      this.addBubble('user', display || note)
      this.input = ''
      try {
        const data = await sendChat(this.patientId, body)
        if (data.assistantReply) this.addBubble('system', data.assistantReply)
        if (data.alertCreated) {
          const level = (data.triage && data.triage.level) || '—'
          this.addBubble('system', '系统已按风险级别通知医护（' + level + '）')
        }
        if (this.pendingTaskId != null) {
          const id = String(this.pendingTaskId)
          if (this.askedTaskIds.indexOf(id) < 0) this.askedTaskIds.push(id)
        }
        this.pendingTaskId = null
        await this.pushNextPlanQuestion({ announceIdle: true })
      } catch (e) {
        this.addBubble('system', '发送失败：' + (e.message || e))
      } finally {
        this.processing = false
      }
    },
    refreshTasks() {
      this.pushNextPlanQuestion({ announceIdle: true, force: true })
    }
  }
}
</script>

<style scoped lang="scss">
.shell {
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: #f3f6f5;
}
.header {
  padding: 16rpx 32rpx 12rpx;
  background: #f3f6f5;
  flex-shrink: 0;
}
.header-row {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
}
.section-title {
  display: block;
  font-size: 32rpx;
  font-weight: 600;
  color: #1e293b;
}
.patient {
  display: block;
  margin-top: 8rpx;
  font-size: 26rpx;
  color: #64748b;
  max-width: 560rpx;
}
.hdr-btn {
  width: 64rpx;
  height: 64rpx;
  border-radius: 50%;
  background: #fff;
  border: 2rpx solid #e2e8f0;
  text-align: center;
  line-height: 64rpx;
  font-size: 32rpx;
  color: #168b76;
}
.content {
  flex: 1;
  height: 0;
  padding: 0 32rpx;
  box-sizing: border-box;
}
.chat-list {
  display: flex;
  flex-direction: column;
  gap: 32rpx;
  padding: 16rpx 0 16rpx;
  min-height: 100%;
  box-sizing: border-box;
}
.empty {
  margin-top: 120rpx;
  text-align: center;
}
.empty-title {
  display: block;
  font-size: 30rpx;
  font-weight: 600;
  color: #1e293b;
}
.empty-sub {
  display: block;
  margin-top: 12rpx;
  font-size: 26rpx;
  color: #94a3b8;
}
.msg {
  display: flex;
  flex-direction: column;
  max-width: 88%;
}
.msg.system {
  align-self: flex-start;
  align-items: flex-start;
}
.msg.user {
  align-self: flex-end;
  align-items: flex-end;
  margin-left: auto;
}
.bubble {
  padding: 28rpx 36rpx;
  border-radius: 36rpx;
  font-size: 30rpx;
  line-height: 1.5;
}
.bubble.system {
  background: #fff;
  color: #1e293b;
  border-bottom-left-radius: 8rpx;
  box-shadow: 0 4rpx 16rpx rgba(0, 0, 0, 0.02);
}
.bubble.user {
  background: #168b76;
  color: #fff;
  border-bottom-right-radius: 8rpx;
  box-shadow: 0 8rpx 24rpx rgba(22, 139, 118, 0.2);
}
.tag {
  display: inline-block;
  font-size: 20rpx;
  font-weight: 700;
  color: #0f6b5a;
  background: #e8f5f2;
  padding: 4rpx 14rpx;
  border-radius: 8rpx;
  margin-bottom: 10rpx;
}
.body {
  white-space: pre-wrap;
  word-break: break-word;
}
.time {
  display: block;
  font-size: 22rpx;
  color: #94a3b8;
  margin-top: 12rpx;
}
.bubble.user .time {
  color: rgba(255, 255, 255, 0.7);
}
.inline-form {
  margin-top: 16rpx;
  width: 100%;
  background: #fff;
  border-radius: 32rpx;
  padding: 24rpx;
  box-sizing: border-box;
  box-shadow: 0 4rpx 16rpx rgba(0, 0, 0, 0.02);
}
.form-prompt {
  font-size: 24rpx;
  color: #64748b;
  margin-bottom: 16rpx;
}
.section-label {
  font-size: 26rpx;
  font-weight: 700;
  color: #0f6b5a;
  margin: 8rpx 0 12rpx;
  letter-spacing: 0.04em;
}
.flabel {
  font-size: 28rpx;
  font-weight: 600;
  color: #1e293b;
  margin-bottom: 12rpx;
}
.opt-row {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
}
.opt {
  border: 2rpx solid #e2e8f0;
  background: #fafcfb;
  color: #1e293b;
  border-radius: 24rpx;
  padding: 12rpx 22rpx;
  font-size: 26rpx;
}
.opt.on, .opt.done {
  background: #168b76;
  color: #fff;
  border-color: #168b76;
}
.opt.done {
  opacity: 0.88;
}
.custom {
  margin-top: 12rpx;
  background: #f8fafc;
  border: 2rpx solid #e2e8f0;
  border-radius: 20rpx;
  padding: 16rpx 20rpx;
  font-size: 26rpx;
}
.custom-done {
  margin-top: 8rpx;
  font-size: 24rpx;
  color: #64748b;
}
.form-actions {
  margin-top: 8rpx;
  display: flex;
  flex-direction: column;
  gap: 16rpx;
}
.skip-link {
  text-align: center;
  font-size: 26rpx;
  color: #64748b;
  padding: 8rpx 0;
}
.skip-panel {
  padding: 8rpx 0 0;
}
.skip-actions {
  margin-top: 16rpx;
  display: flex;
  gap: 16rpx;
  align-items: center;
}
.skip-cancel {
  flex: 1;
  text-align: center;
  font-size: 28rpx;
  color: #64748b;
  padding: 20rpx 0;
}
.skip-confirm {
  flex: 2;
  margin-top: 0;
}
.form-submit {
  margin-top: 8rpx;
  text-align: center;
  background: #168b76;
  color: #fff;
  border-radius: 60rpx;
  padding: 20rpx 0;
  font-size: 28rpx;
  font-weight: 600;
  box-shadow: 0 8rpx 24rpx rgba(22, 139, 118, 0.2);
}
.input-bar {
  flex-shrink: 0;
  padding: 16rpx 32rpx;
  background: #f3f6f5;
  display: flex;
  align-items: flex-end;
  gap: 24rpx;
}
.input-wrap {
  flex: 1;
  background: #fff;
  border: 2rpx solid #e2e8f0;
  border-radius: 48rpx;
  padding: 16rpx 36rpx;
  box-shadow: 0 4rpx 8rpx rgba(0, 0, 0, 0.02);
}
.ta {
  width: 100%;
  min-height: 44rpx;
  max-height: 200rpx;
  font-size: 30rpx;
  line-height: 1.5;
  color: #1e293b;
}
.send {
  width: 88rpx;
  height: 88rpx;
  border-radius: 50%;
  background: #168b76;
  color: #fff;
  text-align: center;
  line-height: 88rpx;
  font-size: 34rpx;
  flex-shrink: 0;
  box-shadow: 0 8rpx 24rpx rgba(22, 139, 118, 0.2);
}
.send.off {
  background: #cbd5e0;
  box-shadow: none;
}
</style>
