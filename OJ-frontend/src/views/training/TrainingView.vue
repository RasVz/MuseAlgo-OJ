<template>
  <div class="training-page">
    <NavbarContext>
      <div class="nav-header">
        <div>
          <div class="nav-title">训练</div>
          <div class="nav-desc">GESP / CSP / NOIP 历年真题与模拟套卷，独立于题库、题单与课程</div>
        </div>
      </div>
    </NavbarContext>

    <div class="main-content">
      <div class="category-bar">
        <el-radio-group v-model="category" @change="fetchList">
          <el-radio-button value="">全部</el-radio-button>
          <el-radio-button v-for="cat in CATEGORIES" :key="cat.value" :value="cat.value">
            {{ cat.label }}
          </el-radio-button>
        </el-radio-group>
      </div>

      <div v-loading="loading" class="paper-grid">
        <el-card
          v-for="paper in papers"
          :key="paper.id"
          shadow="hover"
          class="paper-card"
          @click="goDetail(paper.id)"
        >
          <div class="paper-category">{{ categoryLabel(paper.category) }}</div>
          <div class="paper-title">{{ paper.title }}</div>
          <div v-if="paper.level" class="paper-level">{{ paper.level }}</div>
          <div class="paper-meta">
            <span>{{ paper.problemCount ?? 0 }} 题</span>
            <span>{{ paper.totalScore ?? 100 }} 分</span>
            <span>{{ paper.durationMinutes ?? 120 }} 分钟</span>
          </div>
          <div v-if="paper.description" class="paper-desc">{{ paper.description }}</div>
        </el-card>

        <el-empty v-if="!loading && papers.length === 0" description="暂无训练试卷" />
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * 训练（试卷）列表页：展示 GESP / CSP / NOIP 真题与模拟套卷，支持按类别筛选。
 *
 * 与题库、题单、课程的区别在于「整套限时模拟」语义——这里的一张卡片是一份试卷，
 * 点击进入的是答卷流程而不是单题详情，因此不复用题目列表的卡片组件。
 *
 * 数据来自 getExamPaperList。三个关键点：
 * 1. 类别筛选直接透传给后端分页接口，不在前端过滤——试卷量级可能远超一页，
 *    前端过滤只能看到首页那几条，会漏数据；
 * 2. category 为空串时传 undefined 而非 ''，避免后端把 '' 当成合法类别过滤出空列表；
 * 3. 卡片上的题数/总分/时长都有默认值兜底，缺失字段在模板里做 ?? 兜底，
 *    避免老数据因缺字段渲染成 "undefined 题"。
 */
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import NavbarContext from '@/components/NavbarContext.vue'
import { getExamPaperList } from '@/api/examPaper'
import { unwrapResultData } from '@/utils/response'

const router = useRouter()
// 空串代表「全部」，与 el-radio-group 的 value="" 绑定；不要改成 null，
// null 会被单选组当作未选中而高亮丢失。
const category = ref('')
const papers = ref([])
const loading = ref(false)

// 类别取值必须与后端枚举严格一致（value 用于提交，label 只用于展示）。
// 新增类别时后端枚举与这里要同步改，否则筛选项会提交一个后端不认的值。
const CATEGORIES = [
  { value: 'GESP', label: 'GESP' },
  { value: 'CSP_J', label: 'CSP-J' },
  { value: 'CSP_S', label: 'CSP-S' },
  { value: 'NOIP', label: 'NOIP' },
  { value: 'MOCK', label: '模拟' }
]

// 找不到对应枚举时原样回显后端返回值，而不是显示 undefined：
// 后端新增了类别但前端漏改 CATEGORIES 时，回显原值能让问题被看见而不是变成空白标签。
const categoryLabel = (c) => CATEGORIES.find((x) => x.value === c)?.label || c || '套卷'

/**
 * 拉取试卷列表。
 *
 * 同时用于首次进入（onMounted）与类别切换（单选框 @change）：
 * 切换时整体重查而不是本地过滤，保证「全部」与各分类走同一条服务端分页链路。
 * 只取第 1 页、50 条——页面没有分页器，超过 50 条的试卷需要在
 * 接入分页控件时同步调整 size 与页码管理。
 */
const fetchList = async () => {
  loading.value = true
  try {
    // category 为空时传 undefined，让请求层在序列化时自动丢弃该参数
    const res = await getExamPaperList({ page: 1, size: 50, category: category.value || undefined })
    const data = unwrapResultData(res)
    // ?? [] 兜底：接口异常返回空对象时，papers.value 必须是数组，
    // 否则模板里的 papers.length 会对 undefined 取属性而抛错、整页白屏。
    papers.value = data?.records ?? []
  } finally {
    // 不 catch：交由统一错误处理，finally 只负责复位 loading，
    // 否则筛选失败后 v-loading 会一直挂着不消失。
    loading.value = false
  }
}

const goDetail = (id) => router.push(`/training/${id}`)

onMounted(fetchList)
</script>

<style scoped>
.training-page { min-height: 100vh; background: var(--bg-surface-hover); }
.nav-header { padding: 8px 0; }
.nav-title { font-size: 20px; font-weight: 600; }
.nav-desc { color: var(--text-secondary); font-size: 13px; margin-top: 4px; }
.main-content { max-width: 1100px; margin: 0 auto; padding: 16px; }
.category-bar { margin-bottom: 16px; }
.paper-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 16px; }
.paper-card { cursor: pointer; transition: transform .15s; }
.paper-card:hover { transform: translateY(-2px); }
.paper-category { display: inline-block; font-size: 12px; color: var(--accent-primary); background: var(--el-color-primary-light-9); border-radius: 4px; padding: 2px 8px; }
.paper-title { font-size: 16px; font-weight: 600; margin-top: 8px; }
.paper-level { font-size: 13px; color: var(--accent-warning); margin-top: 4px; }
.paper-meta { display: flex; gap: 14px; font-size: 12px; color: var(--text-secondary); margin-top: 10px; }
.paper-desc { font-size: 13px; color: var(--text-regular); margin-top: 8px; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
</style>
