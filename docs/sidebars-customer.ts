import type {SidebarsConfig} from '@docusaurus/plugin-content-docs';

const sidebars: SidebarsConfig = {
  customerSidebar: [
    'intro',
    {type: 'category', label: 'Getting Started', items: ['onboarding', 'authentication', 'dashboard']},
    {type: 'category', label: 'For Issuers', items: ['issuers/overview', 'issuers/creating-issuance', 'issuers/token-standards', 'issuers/deploying-to-chain', 'issuers/managing-investors', 'issuers/lifecycle']},
    {type: 'category', label: 'For Investors', items: ['investors/overview', 'investors/viewing-investments', 'investors/wallet-setup']},
    {type: 'category', label: 'For Auditors', items: ['auditors/overview', 'auditors/audit-log', 'auditors/token-history']},
    {type: 'category', label: 'Token Standards', items: ['tokens/erc20', 'tokens/erc721', 'tokens/erc1155', 'tokens/erc3643', 'tokens/confidential']},
    'faq',
  ],
};

export default sidebars;
