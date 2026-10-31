<template>
  <div class="edit-view-container">
    <NavBarContext>
      <div class="nav-return-btn">
        <el-button link @click="goBack" class="back-btn-text">
          <el-icon :size="18"><ArrowLeft /></el-icon>
          <span class="back-text">{{ isCreateMode ? '返回题单列表' : '返回题单详情' }}</span>
        </el-button>
        <el-divider direction="vertical" class="nav-divider" />
        <span class="nav-mode-tag">{{ isCreateMode ? '新建模式' : '编辑模式' }}</span>
      </div>

      <!-- 顶部操作区 -->
      <div class="nav-actions">
        <el-button
          v-if="isCreateMode"
          type="primary"
          icon="Check"
          round
          @click="handleSaveTitle"
          :loading="saving"
        >
          {{ isOfficialMode ? '创建官方题单' : isHomeworkMode ? '创建作业' : '创建题单' }}
        </el-button>
        <el-button
          v-if="isHomeworkMode && !isCreateMode"
          type="primary"
          round
          @click="handleSaveHomeworkSettings"
          :loading="savingHomework"
        >
          保存作业设置
        </el-button>
      </div>
    </NavBarContext>

    <div class="content-wrapper">
      <div class="page-header">
        <div class="header-content">
          <div class="title-row">
            <span class="title-prefix">{{ isCreateMode ? '新建题单：' : '正在编辑：' }}</span>
            
            <!-- 新建模式下显示输入框 -->
            <el-input 
              v-if="isCreateMode"
              v-model="pageTitle" 
              placeholder="请输入题单名称 (必填)"
              class="title-input"
              size="large"
              maxlength="50"
              show-word-limit
            />
            
            <!-- 编辑模式下显示只读文本，通过下方的基本设置进行修改 -->
            <span v-else class="title-text">{{ pageTitle || '加载中...' }}</span>
          </div>
          <span class="sub-tips">
            {{ isCreateMode ? '请先输入上方题单名称并点击右上角【创建题单】' : '请在下方的【基本设置】中修改题单信息，或管理题目' }}
          </span>
        </div>
      </div>

      <HomeworkSettingsCard
        v-if="isHomeworkMode"
        ref="homeworkCardRef"
        :is-homework-mode="isHomeworkMode"
      />

      <el-card shadow="always" class="main-card">
          
        <el-tabs v-model="activeTab" class="custom-tabs">
          <el-tab-pane name="info" :disabled="isCreateMode">
            <template #label>
              <span class="custom-tab-label">
                <el-icon><Setting /></el-icon>
                <span>基本设置</span>
              </span>
            </template>
            <!-- 新建模式下，由于还不存在 ID，我们阻断子组件渲染并给予空状态提示 -->
            <div v-if="isCreateMode" class="empty-state">
              <el-empty description="请先在右上角点击【创建题单】" :image-size="120" />
            </div>
            <ProblemListInfoEdit 
              v-else
              :list-id="currentListId"
              :is-create-mode="isCreateMode" 
              @update-title="handleChildUpdateTitle"
              @delete="handleDelete"
            />
          </el-tab-pane>

          <el-tab-pane name="problems" :disabled="isCreateMode">
            <template #label>
              <span class="custom-tab-label">
                <el-icon><Document /></el-icon>
                <span>题目管理</span>
                <el-tag size="small" type="info" round style="margin-left: 5px">{{ problemCount }}</el-tag>
              </span>
            </template>
            <div v-if="isCreateMode" class="empty-state">
              <el-empty description="请先创建题单后再添加题目" :image-size="120" />
            </div>
            <ProblemListProblemsEdit 
              v-else
              :list-id="currentListId" 
              :active="activeTab === 'problems'"
              @update-count="(val) => problemCount = val"
            />
          </el-tab-pane>

        </el-tabs>
      </el-card>
    </div>
  </div>
</template>

<script setup>
// 题单编辑器：一个页面承担三种身份——个人题单、班级题单（作业）与官方题单，
// 靠路由参数（id / query 的 classId、official、mode）区分，保存时走不同的接口。
// 基本信息与题目增删排序拆成两个子组件，保存时统一由本页提交。
import { ref, computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/userStore'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, Setting, Document } from '@element-plus/icons-vue'
import NavBarContext from '@/components/NavbarContext.vue'

// 引入拆分后的组件
import ProblemListInfoEdit from './components/ProblemListInfoEdit.vue'
import ProblemListProblemsEdit from './components/ProblemListProblemsEdit.vue'
import HomeworkSettingsCard from './components/HomeworkSettingsCard.vue'

// 引入操作 API
import { createProblemList, deleteProblemList, getProblemListDetail, updateProblemList } from '@/api/problemList'
import { addClassProblemList } from '@/api/classProblemList'

const route = useRoute()
const router = useRouter()

// 新建模式（无 id / id 为 create 或 0）：两个 Tab 均禁用。
// 子组件都依赖已落库的题单 ID，与其渲染出报错状态，不如直接拦住并提示先创建。
const isCreateMode = computed(() => !route.params.id || route.params.id === 'create' || route.params.id === '0')
const currentListId = computed(() => isCreateMode.value ? null : route.params.id)

const detailData = ref(null)
const classId = computed(() => route.query.classId || detailData.value?.classId || '')
const isHomeworkMode = computed(() => route.query.mode === 'homework' || Number(detailData.value?.type) === 4)
const isClassMode = computed(() => !!classId.value)
const isOfficialMode = computed(() => route.query.official === '1')

const activeTab = ref('info')
const pageTitle = ref('')
const problemCount = ref(0)
const saving = ref(false)
const savingHomework = ref(false)
const homeworkCardRef = ref(null)

const getHomeworkPayload = () => {
  const [startTime, endTime] = homeworkCardRef.value?.getTimeRangeForSubmit?.() ?? [null, null]
  return {
    privacy: homeworkCardRef.value?.getPrivacy?.() ?? 0,
    startTime,
    endTime
  }
}

// 题单 ID 变化（创建成功后 replace 到正式地址，或切换题单）→ 重新拉详情。
// immediate 保证编辑模式下首屏就加载，不用再手动触发。
watch(
  currentListId,
  async (id) => {
    if (!id) {
      detailData.value = null
      return
    }

    try {
      const res = await getProblemListDetail(id)
      if (res.code === 200 && res.data) {
        detailData.value = res.data
        pageTitle.value = res.data.title || ''
        if (route.query.mode === 'homework' || Number(res.data.type) === 4) {
          homeworkCardRef.value?.syncFromDetail?.(res.data)
        }
      }
    } catch (error) {
      console.error('获取题单详情失败', error)
    }
  },
  { immediate: true }
)

// 返回目标随题单身份而变：官方题单回管理后台，班级题单回班级详情的对应 Tab，
// 个人题单回详情页；新建态还没有 ID，只能退回上一页。
const goBack = () => {
  if (isOfficialMode.value) {
    router.push({ name: 'AdminProblemLists' })
    return
  }

  if (isClassMode.value) {
    router.push({
      name: 'AdminClassDetail',
      params: { classId: String(classId.value) },
      query: { tab: isHomeworkMode.value ? 'homework' : 'problemlist' }
    })
    return
  }

  if (isCreateMode.value) {
    router.back()
  } else {
    router.push(`/problem/list/${currentListId.value}`)
  }
}

const handleChildUpdateTitle = (val) => {
  if (!isCreateMode.value && val) {
    pageTitle.value = val
  }
}

// 四种题单身份对应四条创建路径，type 值不可混用：
// 1=个人、2=官方、4=班级作业（班级非作业的题单走 addClassProblemList）。
const handleSaveTitle = async () => {
  if (!pageTitle.value || !pageTitle.value.trim()) {
    ElMessage.warning('题单名称不能为空')
    return
  }

  saving.value = true
  try {
    let res
    const { privacy: homeworkPrivacy, startTime, endTime } = getHomeworkPayload()

    if (isHomeworkMode.value) {
      res = await createProblemList({
        title: pageTitle.value.trim(),
        type: 4,
        classId: Number(classId.value),
        privacy: homeworkPrivacy,
        startTime,
        endTime
      })
    } else if (isClassMode.value) {
      res = await addClassProblemList(classId.value, {
        title: pageTitle.value.trim(),
        description: ''
      })
    } else if (isOfficialMode.value) {
      res = await createProblemList({
        title: pageTitle.value.trim(),
        type: 2,
        privacy: 1
      })
    } else {
      const creatorId = useUserStore().userId

      res = await createProblemList({
        title: pageTitle.value.trim(),
        creatorId,
        auth: 2
      })
    }

    if (res.code === 200) {
      ElMessage.success(
        isHomeworkMode.value
          ? '作业创建成功，可以继续配置题目和时间限制'
          : isOfficialMode.value
            ? '官方题单创建成功，可以继续管理题目和设置'
            : '题单创建成功，可以继续管理题目和设置'
      )
      const newId = (typeof res.data === 'object' && res.data !== null) ? res.data.id : res.data

      // 创建成功后用 replace 把地址换成正式编辑地址：
// 否则用户刷新会回到空白新建页，刚建的题单"消失"了。
      router.replace({
        name: 'ProblemListEdit',
        params: { id: String(newId) },
        query: { ...route.query }
      })
    } else {
      ElMessage.error(res.message || '创建失败')
    }
  } catch (err) {
    console.error(err)
    ElMessage.error('操作异常，请稍后重试')
  } finally {
    saving.value = false
  }
}

// 作业的可见性与起止时间保存后要回写到 detailData：
// 作业设置卡片的 syncFromDetail 依赖它，切 Tab 再切回来才不会显示旧值。
const handleSaveHomeworkSettings = async () => {
  if (!currentListId.value) return
  if (!pageTitle.value || !pageTitle.value.trim()) {
    ElMessage.warning('作业名称不能为空')
    return
  }

  savingHomework.value = true
  try {
    const { privacy: homeworkPrivacy, startTime, endTime } = getHomeworkPayload()
    const res = await updateProblemList({
      id: currentListId.value,
      title: pageTitle.value.trim(),
      type: 4,
      classId: Number(classId.value),
      privacy: homeworkPrivacy,
      startTime,
      endTime
    })

    if (res.code === 200) {
      ElMessage.success('作业设置已保存')
      detailData.value = {
        ...(detailData.value || {}),
        title: pageTitle.value.trim(),
        type: 4,
        classId: Number(classId.value),
        privacy: homeworkPrivacy,
        startTime,
        endTime
      }
    } else {
      ElMessage.error(res.message || '保存失败')
    }
  } catch (error) {
    console.error(error)
    ElMessage.error('保存失败，请稍后重试')
  } finally {
    savingHomework.value = false
  }
}

// 删除题单不可恢复（关联的题目顺序、学生收藏、作业记录一并失效），
// 用 type: 'error' 提高警示等级；用户取消时静默，不弹错误提示。
const handleDelete = () => {
  ElMessageBox.confirm('确定要永久删除该题单吗？删除后所有与之关联的配置均无法恢复！', '危险操作', {
    confirmButtonText: '确定删除',
    cancelButtonText: '取消',
    type: 'error'
  }).then(async () => {
    try {
      const res = await deleteProblemList(currentListId.value)
      if (res.code === 200) {
        ElMessage.success('题单已成功删除')
        goBack()
      } else {
        ElMessage.error(res.message || '删除失败')
      }
    } catch (error) {
      console.error(error)
      ElMessage.error('网络请求失败')
    }
  }).catch(() => {})
}
</script>

<style scoped>
.edit-view-container {
  min-height: calc(100vh - 60px);
  background-color: var(--bg-app);
  padding-bottom: 40px;
}
.content-wrapper { max-width: 1100px; margin: 0 auto; padding: 20px; }

.nav-return-btn { display: flex; align-items: center; height: 100%; }
.back-btn-text { font-size: 15px; color: var(--text-regular); transition: all 0.3s; }
.back-btn-text:hover { color: var(--accent-primary); transform: translateX(-2px); }
.back-text { margin-left: 6px; font-weight: 500; }
.nav-divider { margin: 0 12px; height: 16px; border-color: var(--border-base); }
.nav-mode-tag { font-size: 13px; color: var(--text-secondary); background: var(--bg-surface-hover); padding: 2px 8px; border-radius: 4px; }

.nav-actions { margin-left: auto; display: flex; gap: 12px; align-items: center;}

.page-header { margin-bottom: 24px; margin-top: 10px; }
.header-content { display: flex; flex-direction: column; gap: 8px; }

.title-row { display: flex; align-items: center; gap: 12px; }
.title-prefix { color: var(--text-secondary); font-weight: 400; font-size: 20px; flex-shrink: 0; }

.title-text { font-size: 20px; font-weight: bold; color: var(--text-primary); }

.title-input { max-width: 400px; font-size: 18px; font-weight: bold; }
.title-input :deep(.el-input__wrapper) {
  box-shadow: none !important;
  border-bottom: 2px solid var(--border-base);
  border-radius: 0;
  padding: 0 4px;
  background: transparent;
  transition: border-color 0.3s;
}
.title-input :deep(.el-input__wrapper.is-focus) {
  border-bottom-color: var(--accent-primary);
}

.sub-tips { font-size: 14px; color: var(--text-secondary); }

.main-card { border-radius: 12px; border: none; box-shadow: var(--shadow-md) !important; }
.custom-tabs :deep(.el-tabs__nav-wrap::after) { height: 1px; background-color: var(--border-light); }
.custom-tabs :deep(.el-tabs__item) { font-size: 16px; padding: 0 24px; height: 50px; line-height: 50px; }
.custom-tab-label { display: flex; align-items: center; gap: 6px; }

.empty-state { padding: 60px 0; }
</style>
