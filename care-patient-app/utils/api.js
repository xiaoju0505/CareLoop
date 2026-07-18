import { API_BASE } from './config.js'

function request(method, path, data) {
  const url = API_BASE + path
  return new Promise((resolve, reject) => {
    uni.request({
      url,
      method,
      data: data || {},
      header: {
        'Content-Type': 'application/json'
      },
      timeout: 30000,
      success(res) {
        const status = res.statusCode || 0
        const body = res.data
        if (status >= 200 && status < 300) {
          resolve(body)
          return
        }
        let msg = '请求失败(' + status + ')'
        if (body && typeof body === 'object') {
          msg = body.error || body.message || body.msg || msg
        } else if (typeof body === 'string' && body) {
          msg = body
        }
        reject(new Error(msg))
      },
      fail(err) {
        const raw = (err && (err.errMsg || err.message)) || '网络异常'
        let tip = raw
        if (String(raw).indexOf('request:fail') >= 0) {
          // #ifdef H5
          tip = 'H5 跨域失败。请重新编译运行（已配置代理到公网）；或改用「运行到手机/模拟器」'
          // #endif
          // #ifndef H5
          tip = '网络请求失败：' + raw + '（请确认能打开 ' + url + '）'
          // #endif
        }
        reject(new Error(tip))
      }
    })
  })
}

export function loginByCode(code) {
  return request('POST', '/api/bind/login', { code })
}

export function listTasks(patientId) {
  return request('GET', '/api/patient/mock/tasks?patientId=' + encodeURIComponent(patientId))
}

export function fetchSummary(patientId) {
  return request('GET', '/api/patient/mock/summary?patientId=' + encodeURIComponent(patientId))
}

export function fetchTimeline(patientId) {
  return request('GET', '/api/patient/mock/timeline?patientId=' + encodeURIComponent(patientId))
}

/** sendChat(patientId, text, taskId) 或 sendChat(patientId, bodyObject) */
export function sendChat(patientId, textOrBody, taskId) {
  let body
  if (textOrBody && typeof textOrBody === 'object') {
    body = { ...textOrBody, patientId: String(patientId) }
  } else {
    body = { patientId: String(patientId), text: textOrBody || '' }
    if (taskId != null && taskId !== '') {
      body.taskId = String(taskId)
    }
  }
  return request('POST', '/api/patient/mock/chat', body)
}

export function syncDevice(patientId) {
  return request('POST', '/api/patient/mock/device/sync', {
    patientId: String(patientId)
  })
}
