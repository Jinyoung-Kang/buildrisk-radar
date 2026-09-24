/** 색은 styles/globals.css 의 CSS 변수(라이트·다크)로 정의하고 여기서는 역할 이름만 연결합니다. */
const v = (name) => `rgb(var(--${name}) / <alpha-value>)`;
module.exports = {
  content: ["./pages/**/*.{ts,tsx}", "./components/**/*.{ts,tsx}", "./lib/**/*.{ts,tsx}"],
  darkMode: "media",
  theme: {
    extend: {
      colors: {
        page: v("page"),
        surface: v("surface"),
        raised: v("raised"),
        ink: v("ink"),
        ink2: v("ink2"),
        muted: v("muted"),
        line: v("line"),
        accent: v("accent"),
        crit: v("crit"),
        serious: v("serious"),
        warn: v("warn"),
        good: v("good"),
      },
      fontFamily: {
        sans: ["Pretendard", "system-ui", "-apple-system", "Segoe UI", "Apple SD Gothic Neo", "sans-serif"],
      },
      boxShadow: { card: "0 1px 2px rgb(0 0 0 / 0.04), 0 0 0 1px rgb(var(--line) / 1)" },
    },
  },
  plugins: [],
};
