/** @type {import('tailwindcss').Config} */
export default {
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        panel: "#1e1e2e",
        "panel-alt": "#252537",
        accent: "#7c93ff",
      },
    },
  },
  plugins: [],
};
