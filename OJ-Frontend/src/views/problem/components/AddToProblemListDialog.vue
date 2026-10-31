<template>
  <el-dialog
    :model-value="visible"
    :title="`加入题单 · ${problemTitle || '当前题目'}`"
    width="540px"
    :close-on-click-modal="false"
    @update:model-value="$emit('update:visible', $event)"
    @open="handleOpen"
  >
    <div class="add-to-list-dialog">
      <!-- 搜索 -->
      <div class="search-row">
        <el-input
          v-model="keyword"
          placeholder="搜索我的题单"
          clearable
          :prefix-icon="Search"
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        />
      </div>

      <!-- 题单列表 -->
      <el-scrollbar class="list-scroll" max-height="320px">
        <div v-if="loading" v-loading="loading" class="list-loading"></div>
        <el-empty
          v-else-if="!lists.length"
          description="暂无个人题单，可在下方新建一个"
          :image-size="80"
        />
        <div v-else class="list-item" v-for="item in lists" :key="item.id">
          <div class="item-info">
            <div class="item-title-row">
              <span class="item-title" :class="{ contained: containedIds.has(item.id) }">{{ item.title }}</span>
              <el-tag v-if="item.privacy === 1" size="small" type="success" effect="plain">公开</el-tag>
              <el-tag v-else size="small" type="info" effect="plain">私有</el-tag>
              <el-tag v-if="containedIds.has(item.id)" size="small" type="success">已加入</el-tag>
            </div>
            <div class="item-meta">{{ item.problemCount || 0 }} 题</div>
          </div>
          <div class="item-actions">
            <el-button
              v-if="!containedIds.has(item.id)"
              type="primary"
              size="small"
              :loading="operatingId === item.id"
              @click="handleJoin(item)"
            >
              加入
            </el-button>
            <template v-else>
              <el-button
                type="danger"
                size="small"
                plain
                :loading="operatingId === item.id"
                @click="handleLeave(item)"
              >
                移除
              </el-button>
            </template>
          </div>
        </div>
      </el-scrollbar>

      <!-- 加载更多 -->
      <div v-if="lists.length && hasMore()" class="load-more-row">
        <el-button text type="primary" :loading="loadingMore" @click="loadMore">加载更多</el-button>
      </div>

      <!-- 新建题单 -->
      <div class="create-row">
        <el-input
          v-model="newListTitle"
          placeholder="新建题单标题，创建后自动加入本题"
          maxlength="50"
          @keyup.enter="handleCreateAndJoin"
        >
          <template #append>
            <el-button type="primary" :loading="creating" @click="handleCreateAndJoin">新建并加入</el-button>
          </template>
        </el-input>
      </div>
    </div>

    <template #footer>
      <el-button @click="$emit('update:visible', false)">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
// 「加入题单」弹窗：把当前题目加入用户的题单，可新建题单。
// 已加入的题单标记为不可点——题单内同一道题只能出现一次，
// 界面直接禁用比让用户点进去撞后端的唯一约束报错要好。
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/userStore'
import {
  getProblemLists,
  createProblemList,
  addProblemToList,
  removeProblemFromList,
  getContainedProblemLists
} from '@/api/problemList'

const props = defineProps({
  visible: { type: Boolean, default: false },
  problemId: { type: [Number, String], required: true },
  problemTitle: { type: String, default: '' }
})

defineEmits(['update:visible'])

const PAGE_SIZE = 20

const keyword = ref('')
const lists = ref([])
const containedIds = ref(new Set())
const loading = ref(false)
const loadingMore = ref(false)
const creating = ref(false)
const operatingId = ref(null)
const page = ref(1)
const total = ref(0)
const newListTitle = ref('')

// 用户可能有几十个题单，弹窗里用「加载更多」翻页而不是一次性拉全量。
const hasMore = () => lists.value.length < total.value

const fetchLists = async (reset = false) => {
  const userId = useUserStore().userId
  if (reset) page.value = 1
  const res = await getProblemLists({
    page: page.value,
    size: PAGE_SIZE,
    creatorId: userId,
    keyword: keyword.value || undefined,
    type: 1
  })
  if (res.code === 200) {
    const records = res.data?.records || []
    lists.value = reset ? records : [...lists.value, ...records]
    total.value = res.data?.total || 0
  }
}

// 已包含本题的题单用 Set 存字符串化的 ID：题单 ID 可能在列表与判定接口里
// 一个是数字一个是字符串，统一 String 后 Set.has 才能命中。
const fetchContained = async () => {
  const res = await getContainedProblemLists(props.problemId)
  if (res.code === 200) {
    containedIds.value = new Set((res.data || []).map(String))
  }
}

const handleOpen = async () => {
  loading.value = true
  try {
    await Promise.all([fetchLists(true), fetchContained()])
  } catch {
    // 全局拦截器已统一提示
  } finally {
    loading.value = false
  }
}

const handleSearch = () => {
  loading.value = true
  fetchLists(true)
    .catch(() => {})
    .finally(() => (loading.value = false))
}

const loadMore = async () => {
  loadingMore.value = true
  try {
    page.value += 1
    await fetchLists()
  } catch {
    // 全局拦截器已统一提示
  } finally {
    loadingMore.value = false
  }
}

// 加入/移除成功后本地同步计数与状态，不重拉整页：
// 弹窗内的交互是高频小操作，重拉会让每次点击都有明显延迟。
const handleJoin = async (item) => {
  operatingId.value = item.id
  try {
    await addProblemToList({ problemListId: item.id, problemDisplayId: props.problemId })
    containedIds.value.add(String(item.id))
    item.problemCount = (item.problemCount || 0) + 1
    ElMessage.success(`已加入「${item.title}」`)
  } catch {
    // 全局拦截器已统一提示
  } finally {
    operatingId.value = null
  }
}

const handleLeave = async (item) => {
  operatingId.value = item.id
  try {
    await removeProblemFromList({ problemListId: item.id, problemId: props.problemId })
    containedIds.value.delete(String(item.id))
    item.problemCount = Math.max(0, (item.problemCount || 0) - 1)
    ElMessage.success(`已从「${item.title}」移除`)
  } catch {
    // 全局拦截器已统一提示
  } finally {
    operatingId.value = null
  }
}

// 新建题单并加入本题：两个接口串行，创建成功拿到 ID 后才能加题。
// 新建的题单直接插到列表最前面，省掉用户再点一次「加载更多」去找它。
const handleCreateAndJoin = async () => {
  const title = newListTitle.value.trim()
  if (!title) return ElMessage.warning('请输入题单标题')
  creating.value = true
  try {
    const res = await createProblemList({ title, type: 1 })
    if (res.code === 200) {
      const listId = res.data
      await addProblemToList({ problemListId: listId, problemDisplayId: props.problemId })
      const newItem = { id: listId, title, problemCount: 1, privacy: 0 }
      lists.value.unshift(newItem)
      total.value += 1
      containedIds.value.add(String(listId))
      newListTitle.value = ''
      ElMessage.success('题单已创建并加入本题')
    }
  } catch {
    // 全局拦截器已统一提示
  } finally {
    creating.value = false
  }
}
</script>

<style scoped>
.add-to-list-dialog {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.list-scroll {
  border: 1px solid var(--border-light);
  border-radius: 8px;
}

.list-loading {
  min-height: 120px;
}

.list-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 14px;
  border-bottom: 1px solid var(--border-light);
}

.list-item:last-child {
  border-bottom: none;
}

.item-info {
  min-width: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.item-title-row {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.item-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.item-title.contained {
  color: var(--el-color-success);
}

.item-meta {
  font-size: 12px;
  color: var(--text-secondary);
}

.item-actions {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 8px;
}

.load-more-row {
  text-align: center;
}

.create-row {
  margin-top: 4px;
}
</style>
