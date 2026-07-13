// src/components/AppLayout.jsx
import { Layout, Menu, Button, Typography } from 'antd';
import {
    SafetyCertificateOutlined, FileTextOutlined,
    UploadOutlined, LogoutOutlined,
} from '@ant-design/icons';
import { Link, useNavigate, useLocation } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';

const { Sider, Header, Content } = Layout;
const { Text } = Typography;

export default function AppLayout({ children }) {
    const { user, logout } = useAuth();
    const navigate         = useNavigate();
    const location         = useLocation();

    const handleLogout = () => {
        logout();
        navigate('/login');
    };

    // Derive selected menu key from current URL
    const selectedKey = (() => {
        if (location.pathname.startsWith('/documents/upload'))  return 'upload-doc';
        if (location.pathname.startsWith('/documents'))         return 'documents';
        return 'dashboard';
    })();

    return (
        <Layout style={{ minHeight: '100vh' }}>
            <Sider width={220} theme="dark">
                {/* Logo */}
                <div style={{ padding: '16px 20px', color: 'white', fontSize: 16 }}>
                    <SafetyCertificateOutlined style={{ fontSize: 18, marginRight: 8 }} />
                    <strong>SecAI</strong>
                </div>

                {/* Nav items */}
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
                            key: 'documents',
                            icon: <FileTextOutlined />,
                            label: <Link to="/documents">Documents</Link>,
                        },
                        {
                            key: 'upload-doc',
                            icon: <UploadOutlined />,
                            label: <Link to="/documents/upload">Upload Doc</Link>,
                        },
                    ]}
                />

                {/* Sign out at bottom */}
                <div style={{
                    position: 'absolute',
                    bottom: 16,
                    left: 0,
                    right: 0,
                    padding: '0 12px',
                }}>
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
                    background: '#fff',
                    padding: '0 24px',
                    borderBottom: '1px solid #f0f0f0',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                }}>
                    <Text type="secondary" style={{ fontWeight: 500 }}>
                        {user?.orgName}
                    </Text>
                    <Text type="secondary" style={{ fontSize: 13 }}>
                        {user?.email}
                    </Text>
                </Header>

                <Content>
                    {children}
                </Content>
            </Layout>
        </Layout>
    );
}