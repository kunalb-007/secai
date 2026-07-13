// src/pages/DashboardPage.jsx  — REPLACE ENTIRE FILE
import { Typography, Card, Button, Row, Col } from 'antd';
import { FileTextOutlined, UploadOutlined } from '@ant-design/icons';
import { Link } from 'react-router-dom';
import AppLayout from '../components/AppLayout';

const { Title, Text } = Typography;

export default function DashboardPage() {
    return (
        <AppLayout>
            <div style={{ padding: 24 }}>
                <Title level={4}>Welcome back</Title>
                <Text type="secondary" style={{ display: 'block', marginBottom: 24 }}>
                    Upload your security documents to build your AI knowledge base.
                </Text>

                <Row gutter={16}>
                    <Col span={8}>
                        <Card
                            title="Security Documents"
                            extra={<FileTextOutlined />}
                            actions={[
                                <Link to="/documents/upload" key="upload">
                                    <UploadOutlined /> Upload
                                </Link>,
                                <Link to="/documents" key="list">View All</Link>,
                            ]}
                        >
                            <Text type="secondary">
                                PDF, DOCX, and TXT files are indexed into a searchable AI knowledge base.
                            </Text>
                        </Card>
                    </Col>
                    <Col span={8}>
                        <Card
                            title="Questionnaires"
                            extra={<FileTextOutlined />}
                            style={{ opacity: 0.45 }}
                        >
                            <Text type="secondary">
                                Coming in Phase 4 — upload customer questionnaires for AI answering.
                            </Text>
                        </Card>
                    </Col>
                </Row>
            </div>
        </AppLayout>
    );
}