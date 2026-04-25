import type {Config} from '@docusaurus/types';
import type * as Preset from '@docusaurus/preset-classic';

const config: Config = {
  title: 'Registerwerk',
  tagline: 'Digital Securities Registry — Powered by Blockchain',
  favicon: 'img/favicon.ico',
  url: 'https://docs.registerwerk.io',
  baseUrl: '/',
  onBrokenLinks: 'throw',
  markdown: {
    hooks: {
      onBrokenMarkdownLinks: 'warn',
    },
  },
  i18n: {
    defaultLocale: 'en',
    locales: ['en', 'de'],
  },
  presets: [
    [
      'classic',
      {
        docs: false, // we use separate plugin instances
        blog: false,
        theme: {
          customCss: './src/css/custom.css',
        },
      } satisfies Preset.Options,
    ],
  ],
  plugins: [
    function disableWebpackBarPlugin() {
      return {
        name: 'disable-webpackbar',
        configureWebpack(config: {plugins?: unknown[]}) {
          return {
            plugins: (config.plugins ?? []).filter(plugin =>
              plugin != null && (plugin as {constructor?: {name?: string}}).constructor?.name !== 'WebpackBarPlugin'
            ),
            mergeStrategy: {
              plugins: 'replace' as const,
            },
          };
        },
      };
    },
    [
      '@docusaurus/plugin-content-docs',
      {
        id: 'customer',
        path: 'customer',
        routeBasePath: 'customer',
        sidebarPath: './sidebars-customer.ts',
        editUrl: 'https://github.com/makibytes/registerwerk/edit/main/docs/',
      },
    ],
    [
      '@docusaurus/plugin-content-docs',
      {
        id: 'operator',
        path: 'operator',
        routeBasePath: 'operator',
        sidebarPath: './sidebars-operator.ts',
        editUrl: 'https://github.com/makibytes/registerwerk/edit/main/docs/',
      },
    ],
  ],
  themeConfig: {
    navbar: {
      title: 'Registerwerk',
      logo: {alt: 'Registerwerk', src: 'img/logo.svg', href: '/operator/getting-started'},
      items: [
        {to: '/customer/intro', label: 'For Customers', position: 'left'},
        {to: '/operator/getting-started', label: 'For Operators', position: 'left'},
        {href: 'https://github.com/makibytes/registerwerk', label: 'GitHub', position: 'right'},
      ],
    },
    footer: {
      style: 'dark',
      links: [
        {title: 'Customers', items: [{label: 'Introduction', to: '/customer/intro'}, {label: 'Onboarding', to: '/customer/onboarding'}]},
        {title: 'Operators', items: [{label: 'Getting Started', to: '/operator/getting-started'}, {label: 'Configuration', to: '/operator/configuration/chains'}]},
        {title: 'Standards', items: [{label: 'ERC-3643', href: 'https://erc3643.org'}, {label: 'eWpG', href: 'https://www.gesetze-im-internet.de/ewpg/'}]},
      ],
      copyright: `Copyright ${new Date().getFullYear()} Maki Bytes`,
    },
    prism: {
      theme: {plain: {color: '#393a34', backgroundColor: '#f6f8fa'}, styles: []},
      additionalLanguages: ['java', 'bash', 'yaml', 'solidity'],
    },
  } satisfies Preset.ThemeConfig,
};

export default config;
