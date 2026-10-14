import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useClassContextStore = defineStore('classContext', () => {
  // 当前选中的班级信息（如果为空，说明在 Dashboard）
  const currentClass = ref(null)

  // 进入班级时调用
  function setClass(classInfo) {
    currentClass.value = classInfo
  }

  // 离开班级（返回 Dashboard）时调用
  function clearClass() {
    currentClass.value = null
  }

  return { currentClass, setClass, clearClass }
})