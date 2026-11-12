import cljf from 'clojurific/vite';

// The page's script: todo.client compiled for the browser into
// dist/public/js/main.js, next to the files of public/, which Vite
// copies. dist/public is the Worker's static assets.
export default {
  plugins: [
    cljf({
      outputDir: 'target/client',
      compilerOptions: { main: "'todo.client" },
      manifest: false,
    }),
  ],
  build: {
    outDir: 'dist/public',
    rolldownOptions: {
      // the namespace is the input, the plugin adds it
      input: {},
      output: { entryFileNames: 'js/main.js', codeSplitting: false },
    },
  },
};
