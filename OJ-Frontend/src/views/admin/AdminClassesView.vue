<template>
  <div class="admin-classes">
    <header class="page-header">
      <div>
        <div class="title-line">
          <h1>全部班级</h1>
          <span class="scope-label">跨教师管理</span>
        </div>
        <p>查找班级、了解成员情况，进入班级继续管理教学。</p>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="fetchList">刷新列表</el-button>
    </header>

    <section class="directory-panel" aria-label="班级目录">
      <form class="query-bar" @submit.prevent="search">
        <div class="search-field">
          <label for="class-keyword">查找班级</label>
          <div class="search-control">
            <el-input
              id="class-keyword"
              v-model="draftKeyword"
              :prefix-icon="Search"
              maxlength="100"
              clearable
              placeholder="班级名称 / 机构 / 教师 / 班级 ID"
              @clear="search"
            />
            <el-button native-type="submit" type="primary">搜索</el-button>
          </div>
        </div>
        <div class="filter-field">
          <label for="class-members">成员情况</label>
          <el-select
            id="class-members"
            :model-value="query.memberStatus"
            @change="changeQuery({ memberStatus: $event, page: 1 })"
          >
            <el-option label="全部班级" value="all" /><el-option
              label="有学生"
              value="hasMembers"
            /><el-option label="暂无学生" value="empty" />
          </el-select>
        </div>
        <div class="filter-field sort-field">
          <label for="class-sort">排序方式</label>
          <el-select
            id="class-sort"
            :model-value="query.sort"
            @change="changeQuery({ sort: $event, page: 1 })"
          >
            <el-option label="最新创建" value="newest" /><el-option
              label="最早创建"
              value="oldest"
            /><el-option label="班级名称" value="name" />
          </el-select>
        </div>
      </form>

      <div class="result-bar">
        <span role="status" aria-live="polite"
          ><template v-if="loading">正在加载班级…</template
          ><template v-else-if="error && !classes.length">暂未获取班级数量</template
          ><template v-else
            >{{ hasFilters ? '筛选结果' : '班级目录' }}<b>{{ total }}</b
            ><span class="result-unit">个班级</span></template
          ></span
        >
        <el-button v-if="hasFilters" link type="primary" @click="resetQuery">重置筛选</el-button>
      </div>
      <div v-if="error" class="error-bar" role="alert">
        <div>
          <strong>班级列表加载失败</strong>
          <p>{{ error }}{{ classes.length ? '；下方保留上次加载的结果。' : '' }}</p>
        </div>
        <el-button :loading="loading" @click="fetchList">重试</el-button>
      </div>

      <div class="results" v-loading="loading" :aria-busy="loading">
        <div v-if="!loading && !error && !classes.length" class="empty-state">
          <span class="empty-icon"
            ><el-icon><School /></el-icon
          ></span>
          <h2>{{ hasFilters ? '没有找到匹配的班级' : '还没有班级' }}</h2>
          <p>
            {{
              hasFilters ? '试试其他关键词，或重置筛选条件。' : '教师创建班级后，将在这里统一展示。'
            }}
          </p>
          <el-button v-if="hasFilters" @click="resetQuery">重置筛选</el-button>
        </div>

        <el-table
          v-if="classes.length"
          :data="classes"
          row-key="id"
          class="class-table"
          height="100%"
          :row-style="{ height: '82px' }"
        >
          <el-table-column label="班级" min-width="260">
            <template #default="{ row }">
              <div class="class-identity">
                <span class="class-symbol"
                  ><el-icon><School /></el-icon
                ></span>
                <div class="class-copy">
                  <button class="class-name" :title="row.name" @click="preview(row)">
                    {{ row.name || '未命名班级' }}</button
                  ><span class="class-meta"
                    >#{{ row.id }}<span v-if="row.description"> · {{ row.description }}</span></span
                  >
                </div>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="所属机构" min-width="130" show-overflow-tooltip
            ><template #default="{ row }"
              ><span :class="{ muted: !row.school?.trim() }">{{
                row.school?.trim() || '未填写机构'
              }}</span></template
            ></el-table-column
          >
          <el-table-column label="创建教师" min-width="140" show-overflow-tooltip
            ><template #default="{ row }">{{ classTeacherLabel(row) }}</template></el-table-column
          >
          <el-table-column label="学生人数" width="110"
            ><template #default="{ row }"
              ><span class="member-count" :class="{ muted: !row.studentCount }"
                >{{ row.studentCount ?? 0 }}<small> 人</small></span
              ></template
            ></el-table-column
          >
          <el-table-column label="创建时间" width="155"
            ><template #default="{ row }"
              ><span class="created-time">{{ classCreatedLabel(row.createTime) }}</span></template
            ></el-table-column
          >
          <el-table-column label="操作" width="160" fixed="right"
            ><template #default="{ row }"
              ><div class="row-actions">
                <el-button link @click="preview(row)">预览</el-button
                ><el-button link type="primary" @click="openManage(row)"
                  >进入管理<el-icon><ArrowRight /></el-icon
                ></el-button></div></template
          ></el-table-column>
        </el-table>

        <div v-if="classes.length" class="class-cards">
          <article v-for="row in classes" :key="row.id" class="class-card">
            <div class="card-title">
              <span class="class-symbol"
                ><el-icon><School /></el-icon
              ></span>
              <div class="class-copy">
                <span class="class-meta">班级 #{{ row.id }}</span
                ><button class="class-name" @click="preview(row)">
                  {{ row.name || '未命名班级' }}
                </button>
              </div>
            </div>
            <dl>
              <div>
                <dt>机构</dt>
                <dd>{{ row.school?.trim() || '未填写机构' }}</dd>
              </div>
              <div>
                <dt>教师</dt>
                <dd>{{ classTeacherLabel(row) }}</dd>
              </div>
              <div>
                <dt>学生</dt>
                <dd>{{ row.studentCount ?? 0 }} 人</dd>
              </div>
              <div>
                <dt>创建</dt>
                <dd>{{ classCreatedLabel(row.createTime) }}</dd>
              </div>
            </dl>
            <div class="card-actions">
              <el-button @click="preview(row)">预览班级</el-button
              ><el-button type="primary" plain @click="openManage(row)">进入管理</el-button>
            </div>
          </article>
        </div>
      </div>

      <footer class="pagination-bar">
        <el-select
          :model-value="query.size"
          aria-label="每页班级数量"
          class="page-size"
          @change="changeQuery({ size: $event, page: 1 })"
          ><el-option
            v-for="value in CLASS_PAGE_SIZES"
            :key="value"
            :value="value"
            :label="value + ' 个 / 页'"
        /></el-select>
        <el-pagination
          :current-page="query.page"
          :page-size="query.size"
          :total="total"
          :pager-count="5"
          :disabled="loading"
          layout="prev, pager, next"
          background
          @current-change="changePage"
        />
      </footer>
    </section>
    <ClassDirectoryPreview v-model="previewVisible" :row="selectedClass" @manage="openManage" />
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowRight, Refresh, School, Search } from '@element-plus/icons-vue'
import { getAdminClasses } from '@/api/adminClass'
import { unwrapResultData } from '@/utils/response'
import {
  CLASS_PAGE_SIZES,
  classCreatedLabel,
  classTeacherLabel,
  classesRouteQuery,
  normalizeClassesQuery,
} from '@/utils/adminClassesQuery'
import ClassDirectoryPreview from './class/components/ClassDirectoryPreview.vue'

const route = useRoute()
const router = useRouter()
const query = computed(() => normalizeClassesQuery(route.query))
const draftKeyword = ref('')
const classes = ref([])
const total = ref(0)
const loading = ref(false)
const error = ref('')
const selectedClass = ref(null)
const previewVisible = ref(false)
const hasFilters = computed(() =>
  Boolean(query.value.keyword || query.value.memberStatus !== 'all'),
)
let requestId = 0
let controller

async function fetchList() {
  const id = ++requestId
  controller?.abort()
  controller = new AbortController()
  loading.value = true
  error.value = ''
  try {
    const state = { ...query.value }
    const res = await getAdminClasses(
      { ...state, keyword: state.keyword || undefined },
      { signal: controller.signal, silent: true },
    )
    if (id !== requestId) return
    const data = unwrapResultData(res)
    const lastPage = Math.max(1, Math.ceil((data?.total ?? 0) / state.size))
    // Shared URLs or a deletion elsewhere can leave the requested page beyond the results.
    if (state.page > lastPage) {
      await router.replace({ query: classesRouteQuery({ ...state, page: lastPage }) })
      return
    }
    classes.value = data?.records ?? []
    total.value = data?.total ?? 0
    if (selectedClass.value) {
      selectedClass.value = classes.value.find((row) => row.id === selectedClass.value.id) ?? null
      if (!selectedClass.value) previewVisible.value = false
    }
  } catch (cause) {
    if (id === requestId && cause.code !== 'ERR_CANCELED')
      error.value = cause.message || '请稍后重试'
  } finally {
    if (id === requestId) loading.value = false
  }
}

function changeQuery(patch) {
  const next = { ...query.value, ...patch }
  if (JSON.stringify(next) === JSON.stringify(query.value)) return fetchList()
  return router.push({ query: classesRouteQuery(next) })
}
function search() {
  return changeQuery({ keyword: draftKeyword.value.trim(), page: 1 })
}
function changePage(page) {
  if (!loading.value && page !== query.value.page) return changeQuery({ page })
}
function resetQuery() {
  draftKeyword.value = ''
  return changeQuery({ keyword: '', memberStatus: 'all', sort: 'newest', page: 1 })
}
function preview(row) {
  selectedClass.value = row
  previewVisible.value = true
}
function openManage(row) {
  router.push({ name: 'AdminClassDetail', params: { classId: row.id } })
}

watch(
  () => JSON.stringify(query.value),
  () => {
    draftKeyword.value = query.value.keyword
    previewVisible.value = false
    selectedClass.value = null
    classes.value = []
    const canonical = { path: route.path, query: classesRouteQuery(query.value) }
    if (router.resolve(canonical).fullPath !== route.fullPath) void router.replace(canonical)
    void fetchList()
  },
  { immediate: true },
)

onBeforeUnmount(() => {
  requestId++
  controller?.abort()
})
</script>

<style scoped>
.admin-classes {
  padding: 28px 28px 20px;
  gap: 24px;
  box-sizing: border-box;
  color: var(--text-primary);
  font-family: var(--font-family);
}
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  flex-shrink: 0;
}
.title-line {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
h1 {
  margin: 0;
  font-size: 26px;
  font-weight: 650;
  letter-spacing: -0.5px;
}
.scope-label {
  color: var(--accent-primary);
  background: var(--accent-primary-light);
  border: 1px solid var(--border-light);
  border-radius: 6px;
  padding: 4px 8px;
  font-size: 12px;
}
.page-header p {
  margin: 9px 0 0;
  font-size: 14px;
  color: var(--text-regular);
  line-height: 1.6;
}
.directory-panel {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  border: 1px solid var(--border-light);
  border-radius: 14px;
  background: var(--bg-surface);
  overflow: hidden;
}
.query-bar {
  padding: 22px 24px;
  display: flex;
  align-items: end;
  gap: 16px;
  flex-shrink: 0;
  border-bottom: 1px solid var(--border-light);
}
.search-field {
  flex: 1;
  min-width: 200px;
  max-width: 600px;
}
label {
  display: block;
  margin-bottom: 8px;
  color: var(--text-regular);
  font-size: 12px;
  font-weight: 500;
}
.search-control {
  display: flex;
  gap: 8px;
}
.filter-field {
  width: 145px;
  flex: 0 0 145px;
}
.sort-field {
  margin-left: auto;
}
.result-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 24px;
  font-size: 13px;
  color: var(--text-regular);
  min-height: 24px;
  flex-shrink: 0;
}
.result-bar b {
  font-size: 19px;
  color: var(--text-primary);
  margin: 0 6px 0 12px;
  font-variant-numeric: tabular-nums;
}
.result-unit {
  font-size: 12px;
}
.results {
  flex: 1;
  min-height: 120px;
  position: relative;
  overflow: auto;
}
.class-table {
  width: 100%;
}
.class-table :deep(th.el-table__cell) {
  background: var(--bg-app);
  color: var(--text-regular);
  font-weight: 500;
  font-size: 12px;
  height: 42px;
}
.class-table :deep(.el-table__cell:first-child .cell) {
  padding-left: 24px;
}
.class-identity {
  display: flex;
  gap: 12px;
  align-items: center;
  min-width: 0;
  padding-block: 4px;
}
.class-symbol {
  flex-shrink: 0;
  display: grid;
  place-items: center;
  width: 36px;
  height: 40px;
  border: 1px solid var(--border-light);
  background: var(--bg-app);
  color: var(--accent-primary);
  border-radius: 8px;
  font-size: 19px;
}
.class-copy {
  display: flex;
  flex-direction: column;
  min-width: 0;
  gap: 5px;
  align-items: flex-start;
}
.class-name {
  display: block;
  max-width: 100%;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--text-primary);
  text-align: left;
  font-size: 14px;
  font-weight: 600;
  line-height: 1.5;
  cursor: pointer;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.class-name:hover {
  color: var(--accent-primary);
}
.class-name:focus-visible {
  outline: 2px solid var(--accent-primary);
  outline-offset: 3px;
  border-radius: 2px;
}
.class-meta {
  display: block;
  max-width: 100%;
  font-size: 12px;
  color: var(--text-regular);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.member-count {
  font: 600 16px var(--font-mono);
}
.member-count small {
  font: 12px var(--font-family);
  color: var(--text-regular);
}
.muted,
.created-time {
  color: var(--text-regular);
}
.created-time {
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.row-actions {
  display: flex;
  align-items: center;
  gap: 14px;
}
.row-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}
.row-actions .el-icon {
  margin-left: 4px;
}
.pagination-bar {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 16px 24px;
  border-top: 1px solid var(--border-light);
  flex-shrink: 0;
}
.page-size {
  width: 120px;
}
.error-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding: 14px 24px;
  background: var(--accent-danger-light);
  color: var(--accent-danger);
  flex-shrink: 0;
}
.error-bar strong {
  font-size: 14px;
}
.error-bar p {
  margin: 5px 0 0;
  font-size: 13px;
  overflow-wrap: anywhere;
}
.empty-state {
  padding: 48px 20px;
  text-align: center;
}
.empty-icon {
  display: inline-grid;
  place-items: center;
  width: 60px;
  height: 60px;
  background: var(--bg-app);
  color: var(--text-regular);
  border-radius: 16px;
  font-size: 28px;
}
.empty-state h2 {
  margin: 18px 0 8px;
  font-size: 17px;
  font-weight: 600;
}
.empty-state p {
  margin: 0 0 20px;
  color: var(--text-regular);
  font-size: 14px;
}
.class-cards {
  display: none;
}
@media (max-width: 1100px) {
  .admin-classes {
    padding: 20px 16px 16px;
    gap: 20px;
  }
  .query-bar {
    flex-wrap: wrap;
    padding: 18px;
  }
  .search-field {
    flex-basis: 100%;
    max-width: none;
  }
  .filter-field {
    flex: 1;
    width: auto;
    min-width: 120px;
  }
  .sort-field {
    margin-left: 0;
  }
  .result-bar {
    padding-inline: 18px;
  }
}
@media (max-width: 760px) {
  .admin-classes.admin-classes {
    padding: 18px 12px 12px;
    gap: 18px;
    flex: 0 0 auto;
    min-height: 100%;
  }
  .page-header {
    align-items: flex-start;
    flex-wrap: wrap;
  }
  h1 {
    font-size: 23px;
  }
  .page-header p {
    max-width: 300px;
    font-size: 13px;
  }
  .page-header > .el-button {
    margin-left: auto;
  }
  .directory-panel {
    flex: none;
    min-height: 420px;
    overflow: hidden;
  }
  .query-bar {
    padding: 16px 14px;
    gap: 14px 12px;
  }
  .search-field {
    min-width: 0;
  }
  .search-control :deep(.el-input) {
    min-width: 0;
  }
  .search-control :deep(.el-button) {
    padding-inline: 12px;
  }
  .filter-field {
    min-width: 100px;
  }
  .class-table {
    display: none;
  }
  .results {
    flex: none;
    overflow: visible;
  }
  .class-cards {
    display: grid;
    gap: 12px;
    padding: 0 14px 16px;
  }
  .class-card {
    min-width: 0;
    padding: 16px;
    border: 1px solid var(--border-light);
    border-radius: 10px;
  }
  .card-title {
    display: flex;
    gap: 12px;
    align-items: center;
  }
  .card-title .class-name {
    white-space: normal;
    overflow-wrap: anywhere;
  }
  .class-card dl {
    display: grid;
    gap: 10px;
    margin: 18px 0;
  }
  .class-card dl div {
    display: grid;
    grid-template-columns: 34px minmax(0, 1fr);
    gap: 12px;
    font-size: 13px;
    line-height: 1.5;
  }
  .class-card dt {
    color: var(--text-regular);
  }
  .class-card dd {
    margin: 0;
    overflow-wrap: anywhere;
  }
  .card-actions {
    display: flex;
    gap: 8px;
  }
  .card-actions > .el-button {
    margin: 0;
    flex: 1;
    min-height: 40px;
  }
  .pagination-bar {
    flex-wrap: wrap;
    justify-content: center;
    padding: 16px 10px;
  }
  .pagination-bar :deep(.el-pagination) {
    max-width: 100%;
    --el-pagination-button-width: 28px;
  }
  .pagination-bar :deep(.el-pager li) {
    min-width: 26px;
    margin-inline: 2px;
  }
  .error-bar {
    padding: 14px;
  }
}
</style>
