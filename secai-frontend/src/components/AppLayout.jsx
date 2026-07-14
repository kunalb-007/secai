// src/components/AppLayout.jsx
import { Layout, Menu, Button, Typography } from 'antd';
import {
    SafetyCertificateOutlined, FileTextOutlined, UploadOutlined,
    LogoutOutlined, FileExcelOutlined, PlusOutlined, EyeOutlined,
} from '@ant-design/icons';
import { Link, useNavigate, useLocation } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';

const { Sider, Header, Content } = Layout;
const { Text } = Typography;

export default function AppLayout({ children }) {
    const { user, logout } = useAuth();
    const navigate         = useNavigate();
    const location         = useLocation();

    const handleLogout = () => { logout(); navigate('/login'); };

    const selectedKey = (() => {
        if (location.pathname === '/questionnaires/upload')  return 'q-upload';
        if (location.pathname.endsWith('/review'))           return 'q-review';
        if (location.pathname.startsWith('/questionnaires')) return 'questionnaires';
        if (location.pathname === '/documents/upload')       return 'doc-upload';
        if (location.pathname.startsWith('/documents'))      return 'documents';
        return 'dashboard';
    })();

    // Pull questionnaire ID from review URL so we can link back to it
    const reviewMatch = location.pathname.match(/\/questionnaires\/([^/]+)\/review/);
    const reviewId    = reviewMatch?.[1] ?? null;

    return (
        <Layout style={{ minHeight: '100vh' }}>
            <Sider width={230} theme="dark">
                <div style={{ padding: '16px 20px', color: 'white', fontSize: 16 }}>
                    <SafetyCertificateOutlined style={{ fontSize: 18, marginRight: 8 }} />
                    <strong>SecAI</strong>
                </div>

                <Menu
                    theme="dark"
                    mode="inline"
                    selectedKeys={[selectedKey]}
                    items={[
                        {
                            key: 'dashboard',
                            icon: <SafetyCertificateOutlined />,
                            label: <Link to="/dashboard">Dashboard</Link>,
                        },
                        {
                            type: 'divider',
                            style: { borderColor: 'rgba(255,255,255,0.1)', margin: '8px 0' },
                        },
                        {
                            key: 'docs-group',
                            type: 'group',
                            label: <Text style={{ color: 'rgba(255,255,255,0.45)', fontSize: 11 }}>DOCUMENTS</Text>,
                            children: [
                                { key: 'documents', icon: <FileTextOutlined />, label: <Link to="/documents">All Documents</Link> },
                                { key: 'doc-upload', icon: <UploadOutlined />, label: <Link to="/documents/upload">Upload Doc</Link> },
                            ],
                        },
                        {
                            type: 'divider',
                            style: { borderColor: 'rgba(255,255,255,0.1)', margin: '8px 0' },
                        },
                        {
                            key: 'q-group',
                            type: 'group',
                            label: <Text style={{ color: 'rgba(255,255,255,0.45)', fontSize: 11 }}>QUESTIONNAIRES</Text>,
                            children: [
                                { key: 'questionnaires', icon: <FileExcelOutlined />, label: <Link to="/questionnaires">All Questionnaires</Link> },
                                { key: 'q-upload', icon: <PlusOutlined />, label: <Link to="/questionnaires/upload">Upload Questionnaire</Link> },
                                ...(reviewId ? [{
                                    key: 'q-review',
                                    icon: <EyeOutlined />,
                                    label: <Link to={`/questionnaires/${reviewId}/review`}>Review Answers</Link>,
                                }] : []),
                            ],
                        },
                    ]}
                />

                <div style={{ position: 'absolute', bottom: 16, left: 0, right: 0, padding: '0 12px' }}>
                    <Button
                        type="text"
                        icon={<LogoutOutlined />}
                        onClick={handleLogout}
                        style={{ color: 'rgba(255,255,255,0.65)', width: '100%', textAlign: 'left' }}
                    >
                        Sign Out
                    </Button>
                </div>
            </Sider>

            <Layout>
                <Header style={{
                    background: '#fff', padding: '0 24px',
                    borderBottom: '1px solid #f0f0f0',
                    display: 'flex', alignItems: 'center',
                    justifyContent: 'space-between',
                }}>
                    <Text type="secondary" style={{ fontWeight: 500 }}>{user?.orgName}</Text>
                    <Text type="secondary" style={{ fontSize: 13 }}>{user?.email}</Text>
                </Header>
                <Content>{children}</Content>
            </Layout>
        </Layout>
    );
}