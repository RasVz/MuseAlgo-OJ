import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import {
  getUserInfo,
  updateUserProfile,
  changeUserPassword,
  uploadImage,
  getUserPreferences,
  updateUserPreferences
} from '@/api/user'
import { useUserStore } from '@/stores/userStore'
import { generateAvatar } from '@/utils/dicebear'

/**
 * 用户设置页状态与动作（从 SettingView 抽离）。
 *
 * 三个 Tab（基本资料 / 账号安全 / 偏好设置）共享同一份 form 与 pref，
 * 因此状态集中在本 composable，Tab 组件只做展示与事件转发。
 *
 * 副作用：initProfile 会读 userStore 并请求用户信息与偏好；
 *   saveProfile / saveEmail / savePref 保存成功后回写 store，保证顶栏昵称头像即时更新；
 *   changePassword 成功后会清空登录态并延时跳转登录页。
 *
 * @returns {Object} 响应式的表单与加载态，以及各 Tab 的提交动作
 */
export function useUserSettings() {
  const router = useRouter()
  const userStore = useUserStore()

  const activeTab = ref('profile')
  const loading = ref(false)
  const prefLoading = ref(false)
  const emailSaving = ref(false)
  const avatarUploading = ref(false)

  // --- 个人资料数据 ---
  const form = reactive({
    id: '',
    username: '',
    nickname: '',
    realName: '',
    avatar: '',
    motto: '',
    school: '',
    major: '',
    email: ''
  })

  // --- 密码表单 ---
  const pwdForm = reactive({
    oldPassword: '',
    newPassword: '',
    confirmPassword: ''
  })

  const pwdRules = {
    oldPassword: [{ required: true, message: '请输入当前密码', trigger: 'blur' }],
    newPassword: [
      { required: true, message: '请输入新密码', trigger: 'blur' },
      { min: 6, message: '密码长度不能少于6位', trigger: 'blur' }
    ],
    confirmPassword: [
      { required: true, message: '请确认新密码', trigger: 'blur' },
      {
        validator: (rule, value, callback) => {
          if (value !== pwdForm.newPassword) callback(new Error('两次输入的密码不一致'))
          else callback()
        },
        trigger: 'blur'
      }
    ]
  }

  // --- 偏好设置 ---
  const pref = reactive({
    language: 'cpp',
    fontSize: 14,
    autoComplete: true,
    editorTheme: 'vs-light',
    showLineNumbers: true,
    shareSubmissions: true,
    profilePublic: true,
    notifications: {
      interactions: true,
      homework: true,
      contest: true
    }
  })

  // 偏好回填必须逐字段校验：后端存的是 JSON，布尔字段常以字符串 "false" 落库，
  // 直接赋值会把 "false" 当真值用；非法值一律退回默认值，避免设置页显示异常状态。
  const fillPref = (preferences = {}) => {
    const fontSize = Number(preferences.fontSize)
    const notifications = preferences.notifications || {}

    Object.assign(pref, {
      language: ['cpp', 'java', 'python'].includes(preferences.language) ? preferences.language : 'cpp',
      fontSize: Number.isFinite(fontSize) ? Math.min(24, Math.max(12, fontSize)) : 14,
      autoComplete: typeof preferences.autoComplete === 'boolean'
        ? preferences.autoComplete
        : String(preferences.autoComplete) !== 'false',
      editorTheme: ['vs-light', 'vs-dark'].includes(preferences.editorTheme) ? preferences.editorTheme : 'vs-light',
      showLineNumbers: typeof preferences.showLineNumbers === 'boolean'
        ? preferences.showLineNumbers
        : true,
      shareSubmissions: typeof preferences.shareSubmissions === 'boolean'
        ? preferences.shareSubmissions
        : true,
      profilePublic: typeof preferences.profilePublic === 'boolean'
        ? preferences.profilePublic
        : true,
      notifications: {
        interactions: typeof notifications.interactions === 'boolean' ? notifications.interactions : true,
        homework: typeof notifications.homework === 'boolean' ? notifications.homework : true,
        contest: typeof notifications.contest === 'boolean' ? notifications.contest : true
      }
    })
  }

  const fillForm = (user = {}) => {
    Object.assign(form, {
      id: user.id ?? form.id,
      username: user.username ?? form.username,
      nickname: user.nickname ?? '',
      realName: user.realName ?? '',
      avatar: user.avatar ?? '',
      motto: user.motto ?? '',
      school: user.school ?? '',
      major: user.major ?? '',
      email: user.email ?? form.email ?? ''
    })
  }

  const syncLocalUser = () => {
    userStore.refreshUser()
  }

  const buildProfilePayload = () => ({
    nickname: form.nickname?.trim() || '',
    realName: form.realName?.trim() || '',
    avatar: form.avatar || '',
    motto: form.motto?.trim() || '',
    school: form.school?.trim() || '',
    major: form.major?.trim() || ''
  })

  // --- 初始化 ---
  // 先用 store 里的缓存渲染表单（避免白屏），再用接口结果覆盖；
  // 用户信息与偏好是两个独立接口，分开 try/catch：偏好取失败不该让资料页一起报错。
  const initProfile = async () => {
    const user = userStore.userInfo
    if (!user || !user.id) return
    fillForm(user)

    try {
      const res = await getUserInfo(user.id)
      if (res.code === 200 && res.data) {
        fillForm(res.data)
        syncLocalUser(res.data)
      } else {
        ElMessage.error(res.message || '获取个人资料失败')
      }
    } catch (error) {
      console.error('获取个人资料失败', error)
      ElMessage.error('获取个人资料失败')
    }

    try {
      const res = await getUserPreferences()
      if (res.code === 200 && res.data) {
        fillPref(res.data)
      } else {
        ElMessage.error(res.message || '获取偏好设置失败')
      }
    } catch (error) {
      console.error('获取偏好设置失败', error)
      ElMessage.error('获取偏好设置失败')
    }
  }

  // --- 头像 ---
  // 格式与体积在前端先挡：头像会出现在列表页、榜单页各处，
  // 大图会被反复请求，退回比让用户看到糊图更合理。
  const handleAvatarChange = async (event) => {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (!file) return

    const isImage = ['image/jpeg', 'image/png'].includes(file.type)
    const isLt2M = file.size / 1024 / 1024 < 2

    if (!isImage) {
      ElMessage.error('仅支持 JPG 或 PNG 格式图片')
      return
    }

    if (!isLt2M) {
      ElMessage.error('图片大小不能超过 2MB')
      return
    }

    // 上传与保存是两次请求，任一步失败都要把 form.avatar 还原：
    // 文件已上传但资料保存失败时，不回滚会让界面显示一个后端并不认识的头像。
    const oldAvatar = form.avatar
    avatarUploading.value = true

    try {
      const uploadRes = await uploadImage(file)
      if (uploadRes.code !== 200 || !uploadRes.data) {
        ElMessage.error(uploadRes.msg || '头像上传失败')
        return
      }

      form.avatar = uploadRes.data
      const saveRes = await updateUserProfile(buildProfilePayload())
      if (saveRes.code === 200) {
        if (saveRes.data) {
          fillForm(saveRes.data)
          syncLocalUser(saveRes.data)
        } else {
          syncLocalUser({ avatar: form.avatar })
        }
        ElMessage.success('头像已更新')
      } else {
        form.avatar = oldAvatar
        ElMessage.error(saveRes.msg || '头像保存失败')
      }
    } catch (error) {
      form.avatar = oldAvatar
      console.error('头像上传失败', error)
      ElMessage.error('头像上传失败')
    } finally {
      avatarUploading.value = false
    }
  }

  // 使用 DiceBear 本地生成随机头像（保存后生效）
  const handleRandomAvatar = () => {
    const seed = form.username || form.nickname || `user-${Date.now()}`
    form.avatar = generateAvatar(seed)
    ElMessage.success('已生成随机头像，点击"保存修改"后生效')
  }

  const saveProfile = async () => {
    loading.value = true
    try {
      const res = await updateUserProfile(buildProfilePayload())
      if (res.code === 200) {
        if (res.data) {
          fillForm(res.data)
          syncLocalUser(res.data)
        } else {
          syncLocalUser(buildProfilePayload())
        }
        ElMessage.success('个人资料已更新')
      } else {
        ElMessage.error(res.msg || '个人资料更新失败')
      }
    } catch (error) {
      console.error('个人资料更新失败', error)
      ElMessage.error('个人资料更新失败')
    } finally {
      loading.value = false
    }
  }

  /** 修改密码（表单校验已在 SecurityTab 内完成后触发本动作） */
  // 改密成功后必须清空登录态：旧 token 在密码变更后不应继续可用，
    // 且强制重登能确保用户在其他设备上的会话也失效。延时的 800ms 是留给成功提示的阅读时间。
    const changePassword = async () => {
    try {
      const res = await changeUserPassword({
        oldPassword: pwdForm.oldPassword,
        newPassword: pwdForm.newPassword,
        confirmPassword: pwdForm.confirmPassword
      })

      if (res.code !== 200) {
        ElMessage.error(res.msg || '密码修改失败')
        return
      }

      ElMessage.success('密码修改成功，请重新登录')
      userStore.clearAuth()

      setTimeout(() => {
        router.push('/login')
      }, 800)
    } catch (error) {
      console.error('密码修改失败', error)
      ElMessage.error('密码修改失败')
    }
  }

  const saveEmail = async () => {
    const email = form.email?.trim()
    if (!email) {
      ElMessage.warning('请输入邮箱')
      return
    }
    emailSaving.value = true
    try {
      // 壳子实现：仅保存邮箱（后端已做唯一性校验），验证邮件功能开发中
      const res = await updateUserProfile({
        ...buildProfilePayload(),
        email
      })
      if (res.code === 200) {
        ElMessage.success('邮箱已保存')
      } else {
        ElMessage.error(res.message || res.msg || '邮箱保存失败')
      }
    } catch (error) {
      console.error('邮箱保存失败', error)
      ElMessage.error('邮箱保存失败')
    } finally {
      emailSaving.value = false
    }
  }

  const savePref = async () => {
    prefLoading.value = true
    try {
      const res = await updateUserPreferences({
        language: pref.language,
        fontSize: pref.fontSize,
        autoComplete: pref.autoComplete,
        editorTheme: pref.editorTheme,
        showLineNumbers: pref.showLineNumbers,
        shareSubmissions: pref.shareSubmissions,
        profilePublic: pref.profilePublic,
        notifications: pref.notifications
      })

      if (res.code === 200 && res.data) {
        fillPref(res.data)
        ElMessage.success(res.message || '偏好设置已保存')
      } else {
        ElMessage.error(res.message || '偏好设置保存失败')
      }
    } catch (error) {
      console.error('偏好设置保存失败', error)
      ElMessage.error('偏好设置保存失败')
    } finally {
      prefLoading.value = false
    }
  }

  // 恢复默认不是纯本地重置：默认值必须落库，否则下次进页面又读回用户上次保存的偏好。
  const resetPref = async () => {
    pref.language = 'cpp'
    pref.fontSize = 14
    pref.autoComplete = true
    pref.editorTheme = 'vs-light'
    pref.showLineNumbers = true
    pref.shareSubmissions = true
    pref.profilePublic = true
    pref.notifications = { interactions: true, homework: true, contest: true }
    await savePref()
  }

  return {
    // state
    activeTab, loading, prefLoading, emailSaving, avatarUploading,
    form, pwdForm, pwdRules, pref,
    // actions
    initProfile, handleAvatarChange, handleRandomAvatar,
    saveProfile, changePassword, saveEmail, savePref, resetPref
  }
}
