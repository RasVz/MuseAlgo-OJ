---
theme: default
title: C++ 新手第一课
info: |
  ## C++ 基础教程
  学习 cout 输出语句与字符串基础
class: text-center
drawings:
  persist: false
transition: slide-left
mdc: true

---

# C++ 新手第一课

## `cout` 语句与字符串基础

<div class="pt-12">
  <span @click="$slidev.nav.next" class="px-4 py-2 rounded-lg bg-gradient-to-r from-blue-500 to-purple-500 text-white font-bold cursor-pointer hover:shadow-lg transition-all">
    开始学习 <carbon:arrow-right class="inline"/>
  </span>
</div>

<!--
欢迎来到 C++ 编程的第一课。今天我们将学习如何在控制台输出信息，这是每个程序员的第一步。
-->

---
layout: two-cols
layoutClass: gap-16
---

# 什么是 C++？

C++ 是一种强大的编程语言，广泛用于：
- 🎮 游戏开发
- 💻 系统软件
- 🤖 嵌入式系统
- 🌐 高性能应用

::right::

# 第一个程序的结构

```cpp
#include <iostream>

int main() {
    // 你的代码写在这里
    
    return 0;
}
```

- `#include <iostream>` - 引入输入输出库
- `int main()` - 程序入口点
- `return 0` - 表示程序正常结束

<!--
每个 C++ 程序都需要 main 函数，这是程序开始执行的地方。iostream 库提供了输入输出功能。
-->

---
layout: center
class: "text-center"
---

# 认识 `cout`

## 标准输出流（Console Output）

`cout` 用于在屏幕上**显示信息**

<div class="text-left mx-auto max-w-xl bg-gray-800 rounded-lg p-6 mt-8 shadow-2xl">

```cpp
#include <iostream>

int main() {
    std::cout << "Hello, World!";
    return 0;
}
```

</div>

<div class="mt-6 text-green-400 font-mono text-xl">
▶ 输出结果：Hello, World!
</div>

<!--
cout 发音为 "see-out"，代表 character output。注意使用双引号包裹文本。
-->

---
layout: two-cols
---

# `cout` 语法详解

## 关键符号

| 符号 | 名称 | 作用 |
|------|------|------|
| `<<` | 插入运算符 | 将数据发送到 cout |
| `;` | 分号 | 语句结束标记 |
| `"` | 双引号 | 包裹字符串内容 |

::right::

# 多个输出

使用多个 `<<` 连接内容：

```cpp {all|2|3|4|all}
#include <iostream>

int main() {
    std::cout << "你好" << "，世界！" << std::endl;
    std::cout << "第一行" << std::endl;
    std::cout << "第二行" << std::endl;
    return 0;
}
```

**输出：**
```
你好，世界！
第一行
第二行
```

<!--
std::endl 表示 end line，用于换行。也可以使用 "\n" 来实现换行。
-->

---
layout: center
---

# 字符串（String）基础

## 什么是字符串？

字符串就是**一串字符**，也就是文本数据。

```cpp
"这是一个字符串"
"Hello World"
"12345"  // 注意：这也是字符串，不是数字
"C++ 编程"
```

<div class="grid grid-cols-2 gap-4 mt-8">

<div class="bg-blue-900/30 p-4 rounded-lg border border-blue-500">

### ✅ 正确的字符串
- `"Hello"`
- `"你好 C++"`
- `"2024年"`

</div>

<div class="bg-red-900/30 p-4 rounded-lg border border-red-500">

### ❌ 错误的写法
- `Hello` （缺少引号）
- `"Hello` （缺少右引号）
- `"He said "Hi""` （引号冲突）

</div>

</div>

<!--
在 C++ 中，字符串必须用双引号包裹。如果要包含双引号，需要使用转义字符 \"。
-->

---
layout: two-cols
layoutClass: gap-8
---

# 变量与字符串

使用 `std::string` 类型存储字符串：

```cpp {all|4|5|6|all}
#include <iostream>
#include <string>  // 需要包含 string 库

int main() {
    std::string name = "张三";
    std::string msg = "你好";
    
    std::cout << msg << "，" << name << std::endl;
    
    return 0;
}
```

::right::

<div class="mt-20">

### 输出结果：

<div class="bg-black/50 p-4 rounded font-mono text-green-400">
你好，张三
</div>

<div class="mt-6 text-sm">

💡 **提示：** 使用变量可以：
- 存储数据以便重复使用
- 让程序更灵活
- 方便修改内容

</div>

</div>

<!--
string 类型需要包含 <string> 头文件。变量就像一个盒子，可以存储数据并在需要时取出使用。
-->

---
layout: center
class: "text-center"
---

# 完整示例：自我介绍程序

<div class="text-left mx-auto max-w-2xl bg-gray-900 rounded-xl p-8 shadow-2xl border border-gray-700">

```cpp {all|1-2|4|5-7|9-11|all}
#include <iostream>
#include <string>

int main() {
    std::string name = "小明";
    int age = 18;
    std::string hobby = "编程";
    
    std::cout << "大家好，我叫" << name << std::endl;
    std::cout << "今年" << age << "岁" << std::endl;
    std::cout << "我喜欢" << hobby << std::endl;
    
    return 0;
}
```

</div>

<div class="mt-6 grid grid-cols-3 gap-4 max-w-3xl mx-auto text-sm">
  <div class="bg-blue-500/20 p-2 rounded">包含必要库</div>
  <div class="bg-purple-500/20 p-2 rounded">定义变量</div>
  <div class="bg-green-500/20 p-2 rounded">使用 cout 输出</div>
</div>

<!--
这是一个完整的程序示例，展示了如何组合使用字符串变量和 cout 来输出格式化的文本。
-->

---
layout: two-cols
layoutClass: gap-12
---

# 常见错误与技巧

## ⚠️ 常见错误

| 错误 | 正确 | 说明 |
|------|------|------|
| `cout << Hello;` | `cout << "Hello";` | 字符串需要引号 |
| `cout << "Hi` | `cout << "Hi";` | 缺少右引号 |
| `cout << "a" << b` | `cout << "a" << "b"` | b 未定义 |
| 忘记 `;` | 加上 `;` | 语句结束需要分号 |

::right::

## 💡 实用技巧

<div class="space-y-4 mt-6">

### 换行方法
```cpp
std::cout << "第一行\n";  // 使用 \n
std::cout << "第二行" << std::endl;  // 使用 endl
```

### 连接字符串
```cpp
std::string a = "Hello";
std::string b = "World";
std::cout << a + " " + b;  // Hello World
```

</div>

<!--
endl 和 \n 的区别：endl 会立即刷新缓冲区，而 \n 只是插入换行符。在大量输出时，\n 性能更好。
-->

---
layout: center
class: "text-center"
---

# 课堂练习

## 试着完成以下任务

<div class="grid grid-cols-2 gap-8 max-w-4xl mx-auto mt-8">

<div class="bg-gradient-to-br from-blue-600 to-blue-800 p-6 rounded-xl shadow-lg">

### 📝 练习 1：基础输出

编写程序输出：
```
姓名：你的姓名
班级：你的班级
```

</div>

<div class="bg-gradient-to-br from-purple-600 to-purple-800 p-6 rounded-xl shadow-lg">

### 📝 练习 2：变量使用

使用 `std::string` 变量存储信息，然后输出一段自我介绍。

</div>

</div>

<div class="mt-12">
  <span class="text-gray-400">提示：记得包含必要的头文件，每行语句以分号结束！</span>
</div>

<!--
让学生动手实践是最好的学习方式。鼓励他们尝试修改示例代码，看看会发生什么。
-->

---
layout: end
class: "text-center"
---

# 总结

## 今天学习了

<div class="grid grid-cols-3 gap-6 max-w-5xl mx-auto mt-8 mb-12">

<div class="bg-white/10 p-6 rounded-lg backdrop-blur">
  <div class="text-4xl mb-2">🎯</div>
  <h3 class="font-bold">cout 语句</h3>
  <p class="text-sm opacity-80">使用 << 输出内容到屏幕</p>
</div>

<div class="bg-white/10 p-6 rounded-lg backdrop-blur">
  <div class="text-4xl mb-2">📝</div>
  <h3 class="font-bold">字符串</h3>
  <p class="text-sm opacity-80">用双引号包裹的文本数据</p>
</div>

<div class="bg-white/10 p-6 rounded-lg backdrop-blur">
  <div class="text-4xl mb-2">📦</div>
  <h3 class="font-bold">string 变量</h3>
  <p class="text-sm opacity-80">存储和重复使用字符串</p>
</div>

</div>

## 继续探索 C++ 的精彩世界！

<div class="mt-8">
  <span class="px-6 py-3 bg-gradient-to-r from-green-500 to-teal-500 rounded-full font-bold shadow-lg">
    下节课：变量与数据类型 →
  </span>
</div>