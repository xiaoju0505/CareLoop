const KEY = 'care_app_session'

export function saveSession(s) {
  uni.setStorageSync(KEY, {
    patientId: s.patientId,
    name: s.name || s.patientName || '',
    diagnosis: s.diagnosis || '',
    code: s.code || ''
  })
}

export function getSession() {
  try {
    const raw = uni.getStorageSync(KEY)
    if (!raw) return null
    return typeof raw === 'string' ? JSON.parse(raw) : raw
  } catch (e) {
    return null
  }
}

export function clearSession() {
  uni.removeStorageSync(KEY)
}
