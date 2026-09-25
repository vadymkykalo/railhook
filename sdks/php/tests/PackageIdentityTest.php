<?php

declare(strict_types=1);

namespace Railhook\Tests;

use PHPUnit\Framework\TestCase;
use Railhook\Railhook;

/** The package and the namespace once had unrelated names; this keeps both Railhook. */
class PackageIdentityTest extends TestCase
{
    public function testPackagePublishedAsWebhookPlatformPhp(): void
    {
        $composerJson = json_decode(
            file_get_contents(__DIR__ . '/../composer.json'),
            true
        );

        $this->assertSame('railhook/php', $composerJson['name']);
    }

    public function testSmokeConstructsClientUnderRailhookNamespace(): void
    {
        $client = new Railhook('test_api_key');

        $this->assertInstanceOf(Railhook::class, $client);
    }
}
