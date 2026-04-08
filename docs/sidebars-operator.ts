import type {SidebarsConfig} from '@docusaurus/plugin-content-docs';

const sidebars: SidebarsConfig = {
  operatorSidebar: [
    'getting-started',
    {type: 'category', label: 'Installation', items: ['installation/prerequisites', 'installation/docker', 'installation/backend', 'installation/frontends', 'installation/api-gateway']},
    {type: 'category', label: 'Configuration', items: ['configuration/environment', 'configuration/chains', 'configuration/indexers', 'configuration/identity-provider', 'configuration/email']},
    {type: 'category', label: 'Blockchain Setup', items: ['blockchain/deploying-contracts', 'blockchain/erc3643-setup', 'blockchain/confidential-tokens', 'blockchain/adding-chains']},
    {type: 'category', label: 'Indexers', items: ['indexers/the-graph', 'indexers/solana', 'indexers/resilience']},
    {type: 'category', label: 'Customer Management', items: ['customers/onboarding-flow', 'customers/kyc-process', 'customers/roles']},
    {type: 'category', label: 'Maintenance', items: ['maintenance/monitoring', 'maintenance/backups', 'maintenance/upgrades']},
    'api-reference',
    'troubleshooting',
  ],
};

export default sidebars;
