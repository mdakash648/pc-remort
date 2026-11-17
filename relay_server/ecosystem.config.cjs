module.exports = {
  apps: [
    {
      name: "remotepad-relay",
      script: "server.js",
      cwd: "/opt/remotepad-relay",
      exec_mode: "fork",
      instances: 1,
      autorestart: true,
      max_memory_restart: "120M",
      env: {
        NODE_ENV: "production",
        HOST: "0.0.0.0",
        PORT: 8770,
        UDP_PORT: 8771,
      },
    },
  ],
};
